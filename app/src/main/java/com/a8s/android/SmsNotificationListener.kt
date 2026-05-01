package com.a8s.android

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class SmsNotificationListener : NotificationListenerService() {
    companion object {
        private const val TAG = "A8sNotifListener"
        private const val GOOGLE_MESSAGES_PKG = "com.google.android.apps.messaging"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != GOOGLE_MESSAGES_PKG) return
        
        val extras = sbn.notification.extras ?: return
        val title = extras.getString(Notification.EXTRA_TITLE) ?: return
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        
        if (text.isEmpty()) return

        Log.d(TAG, "Incoming notification from: " + title)
        
        A8sService.instance?.publishIncoming(title, text)
    }
}