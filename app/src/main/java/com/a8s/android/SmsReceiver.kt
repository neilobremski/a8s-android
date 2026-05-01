package com.a8s.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage
import android.util.Log

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        for (pdu in pdus) {
            val msg = SmsMessage.createFromPdu(pdu as ByteArray, format)
            val from = msg.displayOriginatingAddress ?: continue
            val body = msg.displayMessageBody ?: ""
            
            val serviceIntent = Intent(context, A8sService::class.java)
            // Note: This is a simplified pattern. For real production, 
            // you might want a more robust way to pass data to the service.
            // For now, we assume the service is running and can be reached.
            // We should ideally use a singleton or broadcast to the service.
        }
    }
}