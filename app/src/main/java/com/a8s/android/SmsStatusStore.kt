package com.a8s.android

import android.content.Context
import org.json.JSONObject

enum class SmsCallbackKind { SENT, DELIVERY }

data class SmsPartRef(
    val messageId: Int,
    val chunkIndex: Int,
    val chunkCount: Int,
    val partIndex: Int,
    val partCount: Int,
    val maskedRecipient: String,
)

data class SmsCallbackRecord(
    val requestId: Int,
    val peerRequestId: Int,
    val kind: SmsCallbackKind,
    val part: SmsPartRef,
    val createdAtMs: Long,
    val lastProtocolStatus: Int? = null,
)

data class SmsCallbackIds(val sent: Int, val delivery: Int)

/** Body-free persistence for sent callbacks and carrier delivery reports. */
class SmsStatusStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun registerPair(part: SmsPartRef, nowMs: Long = System.currentTimeMillis()): SmsCallbackIds =
        synchronized(LOCK) {
            val sentId = nextRequestId(prefs.getInt(KEY_LAST_REQUEST_ID, 0))
            val deliveryId = nextRequestId(sentId)
            val sent = SmsCallbackRecord(sentId, deliveryId, SmsCallbackKind.SENT, part, nowMs)
            val delivery = SmsCallbackRecord(deliveryId, sentId, SmsCallbackKind.DELIVERY, part, nowMs)
            val committed = prefs.edit()
                .putInt(KEY_LAST_REQUEST_ID, deliveryId)
                .putString(recordKey(sentId), encode(sent))
                .putString(recordKey(deliveryId), encode(delivery))
                .commit()
            check(committed) { "could not persist SMS callback state" }
            SmsCallbackIds(sentId, deliveryId)
        }

    fun find(requestId: Int): SmsCallbackRecord? = synchronized(LOCK) {
        prefs.getString(recordKey(requestId), null)?.let(::decode)
    }

    fun markDeliveryPending(record: SmsCallbackRecord, protocolStatus: Int) = synchronized(LOCK) {
        val updated = record.copy(lastProtocolStatus = protocolStatus)
        prefs.edit().putString(recordKey(record.requestId), encode(updated)).commit()
    }

    fun remove(vararg requestIds: Int) = synchronized(LOCK) {
        val editor = prefs.edit()
        requestIds.forEach { editor.remove(recordKey(it)) }
        editor.commit()
    }

    fun expire(nowMs: Long = System.currentTimeMillis()): List<SmsCallbackRecord> = synchronized(LOCK) {
        val expired = records().filter { isExpired(it, nowMs) }
        if (expired.isNotEmpty()) {
            val editor = prefs.edit()
            expired.forEach { editor.remove(recordKey(it.requestId)) }
            editor.commit()
        }
        expired
    }

    private fun records(): List<SmsCallbackRecord> = prefs.all.mapNotNull { (key, value) ->
        if (!key.startsWith(KEY_RECORD_PREFIX) || value !is String) return@mapNotNull null
        decode(value)
    }

    private fun encode(record: SmsCallbackRecord): String = JSONObject().apply {
        put("request_id", record.requestId)
        put("peer_request_id", record.peerRequestId)
        put("kind", record.kind.name)
        put("message_id", record.part.messageId)
        put("chunk", record.part.chunkIndex)
        put("chunks", record.part.chunkCount)
        put("part", record.part.partIndex)
        put("parts", record.part.partCount)
        put("recipient", record.part.maskedRecipient)
        put("created_at", record.createdAtMs)
        record.lastProtocolStatus?.let { put("last_status", it) }
    }.toString()

    private fun decode(raw: String): SmsCallbackRecord? = try {
        val json = JSONObject(raw)
        SmsCallbackRecord(
            requestId = json.getInt("request_id"),
            peerRequestId = json.getInt("peer_request_id"),
            kind = SmsCallbackKind.valueOf(json.getString("kind")),
            part = SmsPartRef(
                messageId = json.getInt("message_id"),
                chunkIndex = json.getInt("chunk"),
                chunkCount = json.getInt("chunks"),
                partIndex = json.getInt("part"),
                partCount = json.getInt("parts"),
                maskedRecipient = json.getString("recipient"),
            ),
            createdAtMs = json.getLong("created_at"),
            lastProtocolStatus = if (json.has("last_status")) json.getInt("last_status") else null,
        )
    } catch (_: Exception) {
        null
    }

    companion object {
        private val LOCK = Any()
        private const val PREFS_NAME = "a8s_sms_status"
        private const val KEY_LAST_REQUEST_ID = "last_request_id"
        private const val KEY_RECORD_PREFIX = "callback_"
        private const val SENT_EXPIRY_MS = 60L * 60L * 1000L
        private const val DELIVERY_EXPIRY_MS = 72L * 60L * 60L * 1000L

        internal fun nextRequestId(current: Int): Int = if (current == Int.MAX_VALUE) 1 else current + 1

        internal fun isExpired(record: SmsCallbackRecord, nowMs: Long): Boolean {
            val maxAge = when (record.kind) {
                SmsCallbackKind.SENT -> SENT_EXPIRY_MS
                SmsCallbackKind.DELIVERY -> DELIVERY_EXPIRY_MS
            }
            return nowMs - record.createdAtMs >= maxAge
        }

        private fun recordKey(requestId: Int): String = "$KEY_RECORD_PREFIX$requestId"
    }
}
