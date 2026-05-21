package com.a8s.android

class PublishRetryQueue(
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    private val maxBackoffMs: Long = DEFAULT_MAX_BACKOFF_MS,
    private val scheduler: (Long, Runnable) -> Unit = { _, _ -> },
) {
    private val pending = mutableMapOf<String, MutableList<PendingPublish>>()
    var publishFn: ((String, ByteArray) -> Boolean)? = null

    data class PendingPublish(
        val topic: String,
        val payload: ByteArray,
        var attempts: Int = 0,
    )

    @Synchronized
    fun enqueue(remoteName: String, topic: String, payload: ByteArray) {
        val list = pending.getOrPut(remoteName) { mutableListOf() }
        list += PendingPublish(topic, payload)
        A8sAndroid.log("RetryQueue[$remoteName] enqueued (${list.size} pending)")
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
            item.attempts++
            if (publishFn(item.topic, item.payload)) {
                iterator.remove()
            } else {
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
            item.attempts++
            if (item.attempts > maxAttempts) {
                A8sAndroid.log("RetryQueue[$remoteName] discarding after $maxAttempts attempts")
                iterator.remove()
                continue
            }
            if (publishFn(item.topic, item.payload)) {
                A8sAndroid.log("RetryQueue[$remoteName] retry OK (attempt ${item.attempts})")
                iterator.remove()
            } else {
                A8sAndroid.log("RetryQueue[$remoteName] retry failed (attempt ${item.attempts}/$maxAttempts)")
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

    companion object {
        const val DEFAULT_MAX_ATTEMPTS = 10
        const val DEFAULT_MAX_BACKOFF_MS = 30_000L
        const val BASE_BACKOFF_MS = 1_000L
    }
}
