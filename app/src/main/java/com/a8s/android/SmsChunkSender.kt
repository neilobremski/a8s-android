package com.a8s.android

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.telephony.SmsManager
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Submits one logical chunk as multipart SMS and awaits every carrier callback. */
class SmsChunkSender(private val context: Context) {
    private data class PendingResult(
        val requestId: Int,
        val sentIntent: PendingIntent,
        val result: ArrayBlockingQueue<Int>,
    )

    private val requestSequence = AtomicInteger(0)
    private val results = ConcurrentHashMap<Int, ArrayBlockingQueue<Int>>()

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
                smsManager.sendTextMessage(to, null, carrierParts.single(), pending.single().sentIntent, null)
            } else {
                smsManager.sendMultipartTextMessage(
                    to,
                    null,
                    carrierParts,
                    ArrayList(pending.map { it.sentIntent }),
                    null,
                )
            }
        } catch (error: Exception) {
            pending.forEach { results.remove(it.requestId) }
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
        val requestId = requestSequence.incrementAndGet()
        val intent = Intent(A8sService.SMS_SENT_ACTION).apply {
            setPackage(context.packageName)
            putExtra("recipient", to)
            putExtra("part", carrierIndex)
            putExtra("of", carrierCount)
            putExtra("message_id", messageId)
            putExtra("chunk", chunkIndex)
            putExtra("chunks", chunkCount)
            putExtra("request_id", requestId)
        }
        val result = ArrayBlockingQueue<Int>(1)
        results[requestId] = result
        val sentIntent = PendingIntent.getBroadcast(
            context,
            requestId,
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE,
        )
        return PendingResult(requestId, sentIntent, result)
    }

    companion object {
        private const val RESULT_TIMEOUT_MS = 30_000L
    }
}
