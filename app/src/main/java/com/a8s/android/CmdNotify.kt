package com.a8s.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * `/notify <title>|<body>` — post a system notification on the device.
 * The pipe is required to split title from body; if missing, the entire
 * input becomes the body and the title defaults to "a8s".
 */
object CmdNotify {

    private const val CHANNEL_ID = "a8s_push"
    private const val CHANNEL_NAME = "a8s Push"

    // Each notification gets its own ID so they stack rather than
    // replace each other in the shade.
    private val counter = AtomicInteger(2_000)

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val parts = CmdHelpers.parseNotifyArgs(cmd.args)
        if (parts == null) {
            service.replyToSender(config, cmd.sender, "Usage: /notify <title>|<body>")
            return
        }
        val context: Context = service
        ensureChannel(context)
        try {
            val id = counter.incrementAndGet()
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(parts.title)
                .setContentText(parts.body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(parts.body))
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build()
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(id, notif)
            service.replyToSender(
                config, cmd.sender,
                "Posted notification #$id: ${parts.title} | ${parts.body}",
            )
        } catch (e: Exception) {
            A8sAndroid.log("Notify failed: ${e.message}")
            service.replyToSender(config, cmd.sender, "Notify failed: ${e.message}")
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Notifications posted via /notify"
                enableVibration(true)
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(chan)
        }
    }
}
