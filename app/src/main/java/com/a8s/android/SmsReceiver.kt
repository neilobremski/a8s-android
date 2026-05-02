package com.a8s.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        for (msg in messages) {
            val from = msg.displayOriginatingAddress ?: continue
            val body = msg.displayMessageBody ?: ""

            A8sAndroid.log("Received SMS from $from")
            A8sService.instance?.publishIncoming(from, body)
        }
    }
}
