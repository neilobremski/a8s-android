package com.a8s.android

/**
 * Drop inbound SMS/RCS events whose source timestamp is too far in the past.
 *
 * RCS uses [NOTIFICATION_MAX_AGE_MS] against the MessagingStyle message
 * time (falls back to notification post time). SMS/MMS use [SMS_MAX_AGE_MS]
 * to tolerate offline catch-up without replaying a week of thread previews
 * when Google Messages re-posts notifications on app open.
 */
object IngressStaleness {

    /** RCS notification path — MessagingStyle message timestamp. */
    const val NOTIFICATION_MAX_AGE_MS: Long = 6L * 60L * 60L * 1000L

    /** SMS PDU / MMS content-provider dates after offline delivery. */
    const val SMS_MAX_AGE_MS: Long = 48L * 60L * 60L * 1000L

    fun isTooOld(
        eventTimeMs: Long,
        nowMs: Long = System.currentTimeMillis(),
        maxAgeMs: Long,
    ): Boolean {
        if (eventTimeMs <= 0L) return false
        return nowMs - eventTimeMs > maxAgeMs
    }
}
