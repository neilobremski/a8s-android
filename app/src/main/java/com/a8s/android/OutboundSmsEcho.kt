package com.a8s.android

/**
 * Tracks recent outbound SMS segments so multipart carrier echoes that loop
 * back as inbound SMS/RCS are not republished to MQTT.
 *
 * When [A8sService.sendSms] splits a long reply, each part is recorded.
 * Inbound text from the same recipient that exactly matches a recorded part
 * within the window is dropped.
 */
class OutboundSmsEcho(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private data class Entry(val toDigits: String, val part: String, val at: Long)

    private val entries = mutableListOf<Entry>()

    @Synchronized
    fun recordParts(to: String, parts: List<String>, now: Long = System.currentTimeMillis()) {
        if (parts.isEmpty()) return
        evict(now)
        val digits = PhoneNormalize.normalizePhoneDigits(to)
        for (part in parts) {
            val text = part.trim()
            if (text.isEmpty()) continue
            entries += Entry(digits, text, now)
        }
        while (entries.size > maxEntries) {
            entries.removeAt(0)
        }
    }

    @Synchronized
    fun isEcho(fromNumber: String, body: String, now: Long = System.currentTimeMillis()): Boolean {
        val text = body.trim()
        if (text.isEmpty()) return false
        evict(now)
        val fromDigits = PhoneNormalize.normalizePhoneDigits(fromNumber)
        return entries.any { e ->
            e.part == text && PhoneNormalize.phoneDigitsMatch(fromDigits, e.toDigits)
        }
    }

    private fun evict(now: Long) {
        val cutoff = now - windowMs
        entries.removeAll { it.at < cutoff }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 10L * 60L * 1000L
        const val DEFAULT_MAX_ENTRIES: Int = 500
    }
}

private val outboundSmsEcho = OutboundSmsEcho()

fun recordOutboundSmsParts(to: String, parts: List<String>) {
    outboundSmsEcho.recordParts(to, parts)
}

fun isOutboundSmsEcho(fromNumber: String, body: String): Boolean =
    outboundSmsEcho.isEcho(fromNumber, body)
