package com.a8s.android

import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** Helpers for interpreting Google Messages notification payloads. */
object NotificationIngress {

    /**
     * Best-effort event time for staleness checks. Prefer the newest
     * [NotificationCompat.MessagingStyle] message timestamp (actual chat
     * time) over [StatusBarNotification.getPostTime] (re-post time when
     * the Messages app is opened).
     */
    fun eventTimeMs(sbn: StatusBarNotification): Long {
        val style = try {
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification)
        } catch (_: Exception) {
            null
        }
        val messages = style?.messages
        if (!messages.isNullOrEmpty()) {
            return messages.maxOf { it.timestamp }
        }
        return sbn.postTime
    }
}
