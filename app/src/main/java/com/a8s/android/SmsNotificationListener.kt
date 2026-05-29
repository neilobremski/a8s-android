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

        val brief = text.replace("\n", " ").trim().let {
            if (it.length > 200) it.take(200) + "…" else it
        }

        val destDir = File(cacheDir, "media-extract")
        val media = MediaExtractor.extract(this, sbn, destDir)

        if (media.isNotEmpty()) {
            val strategies = media.joinToString(", ") { it.strategy }
            A8sAndroid.log("Intercepted RCS from $title: $brief [+${media.size} media via $strategies]")
            val files = media.map { it.file }
            A8sService.instance?.publishIncoming(title, text, files)
        } else {
            A8sAndroid.log("Intercepted RCS from $title: $brief")
            A8sService.instance?.publishIncoming(title, text)
        }

        // Cache reply action for /reply command
        val replyAction = sbn.notification.actions?.find { action ->
            action.remoteInputs?.isNotEmpty() == true
        }
        if (replyAction != null) {
            A8sAndroid.cacheReplyAction(title, replyAction)
        }
    }
}
