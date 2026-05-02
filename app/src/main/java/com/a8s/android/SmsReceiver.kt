package com.a8s.android

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsMessage

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        
        val bundle = intent.extras ?: return
        val pdus = bundle.get("pdus") as? Array<*> ?: return
        val format = bundle.getString("format")

        for (pdu in pdus) {
            val msg = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                SmsMessage.createFromPdu(pdu as ByteArray, format)
            } else {
                SmsMessage.createFromPdu(pdu as ByteArray)
            } ?: continue
            
            val from = msg.displayOriginatingAddress ?: continue
            val body = msg.displayMessageBody ?: ""
            
            A8sAndroid.log("Received SMS from $from")
            A8sService.instance?.publishIncoming(from, body)
        }
    }
}
