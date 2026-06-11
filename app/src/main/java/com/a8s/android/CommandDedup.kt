package com.a8s.android

/**
 * Bounded-window dedup for inbound slash commands that trigger outbound
 * SMS or RCS reply actions.
 *
 * Two layers:
 * 1. Envelope ULID (`id` field) — broker redelivery of the same MQTT payload.
 * 2. Payload fingerprint — upstream daemon retries that allocate a fresh ULID
 *    but carry the same `/send`, `/reply`, or `/mms` body (issue #36).
 *
 * Pure Kotlin (no Android deps) so it stays unit-testable.
 */
class CommandDedup(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val seenIds = mutableMapOf<String, Long>()
    private val seenPayloads = mutableMapOf<String, Long>()

    /**
     * @return true when the command should execute; false when it is a
     *         duplicate under either dedup layer.
     */
    @Synchronized
    fun shouldExecute(
        envelopeId: String?,
        payloadKey: String?,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        evictExpired(seenIds, now)
        evictExpired(seenPayloads, now)

        val id = envelopeId?.trim().orEmpty()
        if (id.isNotEmpty() && id in seenIds) return false

        val key = payloadKey?.trim().orEmpty()
        if (key.isNotEmpty() && key in seenPayloads) return false

        if (id.isNotEmpty()) {
            seenIds[id] = now
            boundSize(seenIds)
        }
        if (key.isNotEmpty()) {
            seenPayloads[key] = now
            boundSize(seenPayloads)
        }
        return true
    }

    private fun evictExpired(map: MutableMap<String, Long>, now: Long) {
        val cutoff = now - windowMs
        map.entries.filter { it.value < cutoff }.map { it.key }.forEach { map.remove(it) }
    }

    private fun boundSize(map: MutableMap<String, Long>) {
        while (map.size > maxEntries) {
            val oldest = map.entries.minByOrNull { it.value } ?: break
            map.remove(oldest.key)
        }
    }

    /**
     * Gate for inbound `/send`, `/reply`, `/mms` before SMS/RCS fires.
     * Non-SMS commands always pass through.
     */
    fun gateSmsCommand(cmd: MqttRoute.Command, now: Long = System.currentTimeMillis()): Boolean {
        if (cmd.name !in SMS_COMMANDS) return true
        return shouldExecute(cmd.envelopeId, CmdHelpers.outboundSmsDedupKey(cmd), now)
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 5L * 60L * 1000L
        const val DEFAULT_MAX_ENTRIES: Int = 200
        private val SMS_COMMANDS = setOf("send", "reply", "mms")
    }
}

private val inboundCommandDedup = CommandDedup()

/** Process-wide dedup gate for inbound SMS-style slash commands. */
fun gateInboundSmsCommand(cmd: MqttRoute.Command): Boolean =
    inboundCommandDedup.gateSmsCommand(cmd)
