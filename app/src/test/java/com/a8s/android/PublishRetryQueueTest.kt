package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PublishRetryQueueTest {

    private fun makeQueue(maxAttempts: Int = 10): PublishRetryQueue =
        PublishRetryQueue(maxAttempts = maxAttempts, scheduler = { _, _ -> })

    @Test
    fun `enqueue increments pending count`() {
        val q = makeQueue()
        q.enqueue("remote1", "topic/a", "hello".toByteArray())
        assertEquals(1, q.pendingCount("remote1"))
        q.enqueue("remote1", "topic/a", "world".toByteArray())
        assertEquals(2, q.pendingCount("remote1"))
    }

    @Test
    fun `flush delivers all pending when publish succeeds`() {
        val q = makeQueue()
        q.enqueue("r", "t", "a".toByteArray())
        q.enqueue("r", "t", "b".toByteArray())
        val delivered = mutableListOf<String>()
        q.flushOnReconnect("r") { _, payload ->
            delivered += String(payload)
            true
        }
        assertEquals(listOf("a", "b"), delivered)
        assertEquals(0, q.pendingCount("r"))
    }

    @Test
    fun `flush stops on first failure`() {
        val q = makeQueue()
        q.enqueue("r", "t", "a".toByteArray())
        q.enqueue("r", "t", "b".toByteArray())
        q.enqueue("r", "t", "c".toByteArray())
        var callCount = 0
        q.flushOnReconnect("r") { _, _ ->
            callCount++
            callCount == 1
        }
        assertEquals(2, q.pendingCount("r"))
    }

    @Test
    fun `retryNow discards after max attempts`() {
        val q = makeQueue(maxAttempts = 3)
        q.enqueue("r", "t", "x".toByteArray())
        repeat(3) {
            q.retryNow("r") { _, _ -> false }
        }
        q.retryNow("r") { _, _ -> false }
        assertEquals(0, q.pendingCount("r"))
    }

    @Test
    fun `retryNow succeeds on second attempt`() {
        val q = makeQueue()
        q.enqueue("r", "t", "msg".toByteArray())
        q.retryNow("r") { _, _ -> false }
        assertEquals(1, q.pendingCount("r"))
        q.retryNow("r") { _, _ -> true }
        assertEquals(0, q.pendingCount("r"))
    }

    @Test
    fun `independent remotes do not interfere`() {
        val q = makeQueue()
        q.enqueue("r1", "t1", "a".toByteArray())
        q.enqueue("r2", "t2", "b".toByteArray())
        q.flushOnReconnect("r1") { _, _ -> true }
        assertEquals(0, q.pendingCount("r1"))
        assertEquals(1, q.pendingCount("r2"))
    }

    @Test
    fun `clear removes everything`() {
        val q = makeQueue()
        q.enqueue("r1", "t", "a".toByteArray())
        q.enqueue("r2", "t", "b".toByteArray())
        q.clear()
        assertEquals(0, q.pendingCount("r1"))
        assertEquals(0, q.pendingCount("r2"))
    }
}
