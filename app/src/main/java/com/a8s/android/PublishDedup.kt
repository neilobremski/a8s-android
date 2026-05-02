package com.a8s.android

/**
 * Bounded-window dedup cache for outbound MQTT publishes.
 *
 * Google Messages re-posts notifications when a conversation thread
 * updates (e.g. when our forwarded reply lands), and the system can
 * deliver the same SMS through both SmsReceiver and the notification
 * listener. Without dedup, each path triggers `publishIncoming` and
 * the cluster sees N copies of the same message — the duplicate Clover
 * was complaining about in v1.6.0.
 *
 * Pure Kotlin (no Android deps) so it stays unit-testable. The
 * `now`/`System.currentTimeMillis()` injection lets tests pin time
 * without faking the framework.
 */
class PublishDedup(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
    private val seen = mutableMapOf<String, Long>()

    @Synchronized
    fun shouldPublish(key: String, now: Long = System.currentTimeMillis()): Boolean {
        // Evict expired
        val expiredCutoff = now - windowMs
        val expired = seen.entries.filter { it.value < expiredCutoff }.map { it.key }
        expired.forEach { seen.remove(it) }

        if (key in seen) return false
        seen[key] = now
        // Bound size — drop oldest when over.
        while (seen.size > maxEntries) {
            val oldest = seen.entries.minByOrNull { it.value } ?: break
            seen.remove(oldest.key)
        }
        return true
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 5L * 60L * 1000L  // 5 minutes
        const val DEFAULT_MAX_ENTRIES: Int = 100
    }
}
