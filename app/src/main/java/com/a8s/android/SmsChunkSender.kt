package com.a8s.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Submits one logical chunk as multipart SMS and awaits every carrier callback. */
class SmsChunkSender(private val context: Context) {
    private data class PendingResult(
        val requestId: Int,
        val sentIntent: PendingIntent,
        val deliveryRequestId: Int,
        val deliveryIntent: PendingIntent,
        val result: ArrayBlockingQueue<Int>,
    )

    private val results = ConcurrentHashMap<Int, ArrayBlockingQueue<Int>>()
    private val statusStore = SmsStatusStore(context.applicationContext)

    fun complete(requestId: Int, resultCode: Int) {
        results.remove(requestId)?.offer(resultCode)
    }

    fun clear() {
        results.clear()
    }

    @Suppress("LongParameterList")
    fun send(
        to: String,
        messageId: Int,
        chunkIndex: Int,
        chunkCount: Int,
        carrierParts: ArrayList<String>,
        smsManager: SmsManager,
    ): Boolean {
        val pending = carrierParts.indices.map { carrierIndex ->
            pendingResult(to, messageId, chunkIndex, chunkCount, carrierIndex, carrierParts.size)
        }
        try {
            if (carrierParts.size == 1) {
                smsManager.sendTextMessage(
                    to,
                    null,
                    carrierParts.single(),
                    pending.single().sentIntent,
                    pending.single().deliveryIntent,
                )
            } else {
                smsManager.sendMultipartTextMessage(
                    to,
                    null,
                    carrierParts,
                    ArrayList(pending.map { it.sentIntent }),
                    ArrayList(pending.map { it.deliveryIntent }),
                )
            }
        } catch (error: Exception) {
            pending.forEach {
                results.remove(it.requestId)
                statusStore.remove(it.requestId, it.deliveryRequestId)
            }
            throw error
        }
        for (item in pending) {
            val rc = item.result.poll(RESULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            results.remove(item.requestId)
            if (rc == null || rc != android.app.Activity.RESULT_OK) {
                pending.forEach { results.remove(it.requestId) }
                val reason = if (rc == null) "callback timeout" else "failed carrier part"
                A8sAndroid.log("SMS message $messageId halted after $reason in chunk ${chunkIndex + 1}/$chunkCount")
                return false
            }
        }
        return true
    }

    @Suppress("LongParameterList")
    private fun pendingResult(
        to: String,
        messageId: Int,
        chunkIndex: Int,
        chunkCount: Int,
        carrierIndex: Int,
        carrierCount: Int,
    ): PendingResult {
        val ids = statusStore.registerPair(
            SmsPartRef(
                messageId = messageId,
                chunkIndex = chunkIndex,
                chunkCount = chunkCount,
                partIndex = carrierIndex,
                partCount = carrierCount,
                maskedRecipient = PhoneNormalize.maskNumber(to),
            ),
        )
        val result = ArrayBlockingQueue<Int>(1)
        results[ids.sent] = result
        return try {
            val sentIntent = callbackIntent(SmsStatusReceiver.ACTION_SENT, ids.sent, oneShot = true)
            val deliveryIntent = callbackIntent(SmsStatusReceiver.ACTION_DELIVERY, ids.delivery, oneShot = false)
            PendingResult(ids.sent, sentIntent, ids.delivery, deliveryIntent, result)
        } catch (error: Exception) {
            results.remove(ids.sent)
            statusStore.remove(ids.sent, ids.delivery)
            throw error
        }
    }

    private fun callbackIntent(action: String, requestId: Int, oneShot: Boolean): PendingIntent {
        val intent = Intent(context, SmsStatusReceiver::class.java).apply {
            this.action = action
            data = Uri.Builder()
                .scheme("a8s-sms")
                .authority("callback")
                .appendPath(requestId.toString())
                .build()
        }
        val flags = PendingIntent.FLAG_MUTABLE or if (oneShot) PendingIntent.FLAG_ONE_SHOT else 0
        return PendingIntent.getBroadcast(context, requestId, intent, flags)
    }

    companion object {
        private const val RESULT_TIMEOUT_MS = 30_000L
    }
}
