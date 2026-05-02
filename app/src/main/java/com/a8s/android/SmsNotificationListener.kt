package com.a8s.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

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
        A8sAndroid.log("Intercepted RCS from $title: $brief")
        A8sService.instance?.publishIncoming(title, text)
    }
}
