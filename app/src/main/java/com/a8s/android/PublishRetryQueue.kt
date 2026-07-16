package com.a8s.android

import org.json.JSONObject

class PublishRetryQueue(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val scheduler: (Long, Runnable) -> Unit = { _, _ -> },
) {
    private val pending = mutableMapOf<String, MutableList<PendingPublish>>()
    var publishFn: ((String, ByteArray) -> Boolean)? = null
    var resultListener: ((String, ByteArray, Result) -> Unit)? = null

    sealed class Result {
        data object Accepted : Result()
        data class Exhausted(val attempts: Int) : Result()
    }

    data class PendingPublish(
        val topic: String,
        val payload: ByteArray,
        var attempts: Int = 0,
    )

    @Synchronized
    fun enqueue(remoteName: String, topic: String, payload: ByteArray) {
        val list = pending.getOrPut(remoteName) { mutableListOf() }
        list += PendingPublish(topic, payload)
        A8sAndroid.log(
            "RetryQueue[$remoteName] enqueued id=${payloadId(payload)} (${list.size} pending)",
        )
        recordRetry(remoteName, payload, TransactionTrace.Status.PARTIAL, "queued for retry")
        scheduleRetry(remoteName)
    }

    @Synchronized
    fun flushOnReconnect(remoteName: String, publishFn: (String, ByteArray) -> Boolean) {
        val list = pending[remoteName] ?: return
        if (list.isEmpty()) return
        A8sAndroid.log("RetryQueue[$remoteName] flushing ${list.size} pending on reconnect")
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.attempts >= maxAttempts) {
                reportExhausted(remoteName, item)
                iterator.remove()
                continue
            }
            item.attempts++
            if (publishFn(item.topic, item.payload)) {
                A8sAndroid.log("RetryQueue[$remoteName] flush accepted id=${payloadId(item.payload)}")
                resultListener?.invoke(remoteName, item.payload, Result.Accepted)
                iterator.remove()
            } else {
                if (item.attempts >= maxAttempts) {
                    reportExhausted(remoteName, item)
                    iterator.remove()
                    continue
                }
                break
            }
        }
        if (list.isEmpty()) {
            pending.remove(remoteName)
        } else {
            A8sAndroid.log("RetryQueue[$remoteName] ${list.size} still pending after flush")
            scheduleRetry(remoteName)
        }
    }

    @Synchronized
    fun retryNow(remoteName: String, publishFn: (String, ByteArray) -> Boolean) {
        val list = pending[remoteName] ?: return
        if (list.isEmpty()) {
            pending.remove(remoteName)
            return
        }
        val iterator = list.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            if (item.attempts >= maxAttempts) {
                reportExhausted(remoteName, item)
                iterator.remove()
                continue
            }
            item.attempts++
            if (publishFn(item.topic, item.payload)) {
                A8sAndroid.log(
                    "RetryQueue[$remoteName] retry accepted id=${payloadId(item.payload)} " +
                        "(attempt ${item.attempts})",
                )
                resultListener?.invoke(remoteName, item.payload, Result.Accepted)
                iterator.remove()
            } else {
                A8sAndroid.log(
                    "RetryQueue[$remoteName] retry failed id=${payloadId(item.payload)} " +
                        "(attempt ${item.attempts}/$maxAttempts)",
                )
                if (item.attempts >= maxAttempts) {
                    reportExhausted(remoteName, item)
                    iterator.remove()
                    continue
                }
                break
            }
        }
        if (list.isEmpty()) {
            pending.remove(remoteName)
        } else {
            scheduleRetry(remoteName)
        }
    }

    private fun scheduleRetry(remoteName: String) {
        val list = pending[remoteName] ?: return
        val next = list.firstOrNull() ?: return
        val delayMs = backoffMs(next.attempts)
        scheduler(delayMs) {
            val fn = publishFn ?: return@scheduler
            retryNow(remoteName, fn)
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val ms = BASE_BACKOFF_MS * (1L shl attempt.coerceAtMost(10))
        return ms.coerceAtMost(maxBackoffMs)
    }

    @Synchronized
    fun pendingCount(remoteName: String): Int = pending[remoteName]?.size ?: 0

    @Synchronized
    fun clear() {
        pending.clear()
    }

    private fun payloadId(payload: ByteArray): String = try {
        JSONObject(String(payload)).optString("id").take(8).ifEmpty { "?" }
    } catch (_: Exception) {
        "?"
    }

    private fun recordRetry(
        remoteName: String,
        payload: ByteArray,
        status: TransactionTrace.Status,
        summary: String,
    ) {
        val meta = MqttPublishDiagnostics.metadata(payload)
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = meta.envelopeId,
                flow = "MQTT_RETRY",
                status = status,
                from = meta.from,
                to = meta.to,
                summary = "$summary on $remoteName",
            ),
        )
    }

    private fun reportExhausted(remoteName: String, item: PendingPublish) {
        A8sAndroid.log(
            "RetryQueue[$remoteName] discarding id=${payloadId(item.payload)} " +
                "after $maxAttempts attempts",
        )
        recordRetry(
            remoteName,
            item.payload,
            TransactionTrace.Status.FAIL,
            "discarded after $maxAttempts attempts",
        )
        resultListener?.invoke(remoteName, item.payload, Result.Exhausted(maxAttempts))
    }

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
        const val DEFAULT_MAX_BACKOFF_MS = 30_000L
        const val BASE_BACKOFF_MS = 1_000L
    }
}
