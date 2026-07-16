package com.a8s.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent) ?: return
        val first = messages.firstOrNull() ?: return
        val from = first.displayOriginatingAddress ?: return
        val body = messages.joinToString("") { it.displayMessageBody.orEmpty() }
        val eventTimeMs = messages.minOf { it.timestampMillis }

        val brief = body.replace("\n", " ").trim().let {
            if (it.length > 200) it.take(200) + "…" else it
        }
        A8sAndroid.log("Received SMS from $from: $brief")
        A8sService.instance?.publishIncoming(
            IncomingSmsRouter.IncomingMessage(
                fromIdentity = from,
                body = body,
                ingress = IncomingSmsRouter.IngressMeta(
                    source = IngressSource.SMS,
                    sourceEventId = "$from|$eventTimeMs|${hashIngressIdentity(body)}",
                    eventTimeMs = eventTimeMs,
                    maxAgeMs = IngressStaleness.SMS_MAX_AGE_MS,
                ),
            ),
        )
    }
}
