package com.a8s.android

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import android.telephony.SmsMessage

/** Receives body-free sent and delivery callbacks even when the service process was restarted. */
class SmsStatusReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.data?.lastPathSegment?.toIntOrNull() ?: return
        val store = SmsStatusStore(context.applicationContext)
        val record = store.find(requestId) ?: return
        when {
            intent.action == ACTION_SENT && record.kind == SmsCallbackKind.SENT -> {
                handleSent(store, record, resultCode)
                A8sService.instance?.completeSmsSent(requestId, resultCode)
            }
            intent.action == ACTION_DELIVERY && record.kind == SmsCallbackKind.DELIVERY -> {
                handleDelivery(store, record, SmsDeliveryDecoder.decode(intent))
            }
        }
    }

    private fun handleSent(store: SmsStatusStore, record: SmsCallbackRecord, resultCode: Int) {
        val verdict = when (resultCode) {
            Activity.RESULT_OK -> "OK"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "GENERIC_FAILURE"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "NO_SERVICE"
            SmsManager.RESULT_ERROR_NULL_PDU -> "NULL_PDU"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "RADIO_OFF"
            else -> "rc=$resultCode"
        }
        A8sAndroid.log("SMS send result $verdict ${partSummary(record)}")
        store.remove(record.requestId)
        if (resultCode != Activity.RESULT_OK) store.remove(record.peerRequestId)
    }

    private fun handleDelivery(
        store: SmsStatusStore,
        record: SmsCallbackRecord,
        observation: SmsDeliveryObservation,
    ) {
        val raw = observation.protocolStatus?.let { " status=0x${it.toUInt().toString(16)}" }.orEmpty()
        A8sAndroid.log("SMS delivery ${observation.classification.name}$raw ${partSummary(record)}")
        when (observation.classification) {
            SmsDeliveryClass.PENDING -> store.markDeliveryPending(record, observation.protocolStatus ?: -1)
            SmsDeliveryClass.POSITIVE, SmsDeliveryClass.NEGATIVE -> store.remove(record.requestId)
            SmsDeliveryClass.UNDECODABLE -> Unit
        }
    }

    private fun partSummary(record: SmsCallbackRecord): String {
        val part = record.part
        return "for message ${part.messageId} to ${part.maskedRecipient} " +
            "(chunk ${part.chunkIndex + 1}/${part.chunkCount}, part ${part.partIndex + 1}/${part.partCount})"
    }

    companion object {
        const val ACTION_SENT = "com.a8s.android.SMS_SENT"
        const val ACTION_DELIVERY = "com.a8s.android.SMS_DELIVERY"
    }
}

data class SmsDeliveryObservation(
    val classification: SmsDeliveryClass,
    val protocolStatus: Int? = null,
)

object SmsDeliveryDecoder {
    fun decode(intent: Intent): SmsDeliveryObservation {
        val pdu = intent.getByteArrayExtra("pdu")
            ?: return SmsDeliveryObservation(SmsDeliveryClass.UNDECODABLE)
        val format = intent.getStringExtra("format")
            ?: return SmsDeliveryObservation(SmsDeliveryClass.UNDECODABLE)
        if (format != SmsDeliveryStatus.FORMAT_3GPP && format != SmsDeliveryStatus.FORMAT_3GPP2) {
            return SmsDeliveryObservation(SmsDeliveryClass.UNDECODABLE)
        }
        val message = try {
            SmsMessage.createFromPdu(pdu, format)
        } catch (_: RuntimeException) {
            null
        } ?: return SmsDeliveryObservation(SmsDeliveryClass.UNDECODABLE)
        if (!message.isStatusReportMessage) return SmsDeliveryObservation(SmsDeliveryClass.UNDECODABLE)
        val status = message.status
        return SmsDeliveryObservation(SmsDeliveryStatus.classify(format, status), status)
    }
}
