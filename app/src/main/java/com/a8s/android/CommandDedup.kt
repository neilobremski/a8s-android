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
    private val store: FileDedupStore? = null,
) {
    private val seenIds = mutableMapOf<String, Long>()
    private val seenPayloads = mutableMapOf<String, Long>()
    private val persistEnabled: Boolean = store != null

    init {
        if (persistEnabled) {
            store?.load()?.let { seenPayloads.putAll(it) }
        }
    }

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
            if (persistEnabled) {
                store?.save(seenPayloads)
            }
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

/** Process-wide dedup gate for inbound SMS-style slash commands. */
fun gateInboundSmsCommand(service: A8sService, cmd: MqttRoute.Command): Boolean =
    service.inboundCommandDedup.gateSmsCommand(cmd)

/**
 * Dedup for commands that originate over SMS/RCS. A single inbound
 * message can surface both via [SmsReceiver] and via the Google Messages
 * notification (which also re-posts on thread updates), so every verb —
 * not just the SMS-sending ones — needs a gate before it executes.
 *
 * Keyed on the matched phonebook participant (not the raw number) so the
 * two delivery paths — which can present the sender as `+1…` digits vs a
 * Contacts-resolved number — collapse to the same key.
 */
fun gateSmsOriginCommand(service: A8sService, participant: String, body: String): Boolean =
    service.smsOriginCommandDedup.shouldExecute(
        envelopeId = null,
        payloadKey = "smsorigin|$participant|${body.trim()}",
    )

/**
 * Dedup for inbound phone-agent envelopes forwarded to SMS. Stops broker
 * redelivery / upstream retries from amplifying into multiple texts.
 */
fun gatePhoneAgentForward(service: A8sService, envelopeId: String, targetAgent: String, content: String): Boolean =
    service.phoneAgentForwardDedup.shouldExecute(
        envelopeId = envelopeId,
        payloadKey = "phonefwd|$targetAgent|${content.trim()}",
    )
