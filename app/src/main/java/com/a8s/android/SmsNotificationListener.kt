package com.a8s.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.io.File

class SmsNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.google.android.apps.messaging") return

        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        if (text.isEmpty()) return

        val eventTimeMs = NotificationIngress.eventTimeMs(sbn)
        if (IngressStaleness.isTooOld(
                eventTimeMs,
                maxAgeMs = IngressStaleness.NOTIFICATION_MAX_AGE_MS,
            )) {
            A8sAndroid.log(
                "Ignored stale RCS notification from $title " +
                    "(event ${(System.currentTimeMillis() - eventTimeMs) / 60_000}m ago)",
            )
            return
        }

        val brief = text.replace("\n", " ").trim().let {
            if (it.length > 200) it.take(200) + "…" else it
        }

        // Extract reply action for /reply command (keyed by phone number in publishIncoming)
        val replyAction = sbn.notification.actions?.find { action ->
            action.remoteInputs?.isNotEmpty() == true
        }

        // Extract media on a worker thread to avoid blocking the listener
        Thread {
            val destDir = File(cacheDir, "media-extract")
            val media = MediaExtractor.extract(this, sbn, destDir)

            if (media.isNotEmpty()) {
                val strategies = media.joinToString(", ") { it.strategy }
                A8sAndroid.log("Intercepted RCS from $title: $brief [+${media.size} media via $strategies]")
                val files = media.map { it.file }
                A8sService.instance?.publishIncoming(
                    IncomingSmsRouter.IncomingMessage(
                        fromIdentity = title,
                        body = text,
                        mediaFiles = files,
                        replyAction = replyAction,
                        ingress = IncomingSmsRouter.IngressMeta(
                            eventTimeMs = eventTimeMs,
                            maxAgeMs = IngressStaleness.NOTIFICATION_MAX_AGE_MS,
                        ),
                    ),
                )
            } else {
                A8sAndroid.log("Intercepted RCS from $title: $brief")
                A8sService.instance?.publishIncoming(
                    IncomingSmsRouter.IncomingMessage(
                        fromIdentity = title,
                        body = text,
                        replyAction = replyAction,
                        ingress = IncomingSmsRouter.IngressMeta(
                            eventTimeMs = eventTimeMs,
                            maxAgeMs = IngressStaleness.NOTIFICATION_MAX_AGE_MS,
                        ),
                    ),
                )
            }
        }.start()
    }
}
