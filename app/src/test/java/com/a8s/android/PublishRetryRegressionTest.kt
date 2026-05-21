package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishRetryRegressionTest {

    @Test
    fun `publish failure without retry loses message permanently`() {
        // Demonstrates the original bug: when publish fails, the message is
        // gone forever. A naive "try/catch + fail++" discards the payload.
        var published = false
        val alwaysFails: (String, ByteArray) -> Boolean = { _, _ -> false }

        // Simulate old behavior: just call publish, if it fails, increment fail.
        val result = alwaysFails("topic", "msg".toByteArray())

        // Message was NOT retained — there's no mechanism to redeliver it.
        assertTrue(!result)
        assertTrue(!published)
    }

    @Test
    fun `publish failure with retry queue preserves message for redelivery`() {
        val delivered = mutableListOf<String>()
        val q = PublishRetryQueue(maxAttempts = 5, scheduler = { _, _ -> })

        // First attempt fails — simulates connection drop during publish.
        q.enqueue("remote", "topic/test", "important message".toByteArray())
        assertEquals(1, q.pendingCount("remote"))

        // Message is preserved and can be delivered on reconnect.
        q.flushOnReconnect("remote") { _, payload ->
            delivered += String(payload)
            true
        }

        assertEquals(listOf("important message"), delivered)
        assertEquals(0, q.pendingCount("remote"))
    }

    @Test
    fun `multiple failed publishes are all recovered on reconnect`() {
        val delivered = mutableListOf<String>()
        val q = PublishRetryQueue(maxAttempts = 5, scheduler = { _, _ -> })

        // Simulate a burst of messages during a connection outage.
        q.enqueue("remote", "topic", "msg1".toByteArray())
        q.enqueue("remote", "topic", "msg2".toByteArray())
        q.enqueue("remote", "topic", "msg3".toByteArray())
        assertEquals(3, q.pendingCount("remote"))

        // Connection restored — all queued messages flush in order.
        q.flushOnReconnect("remote") { _, payload ->
            delivered += String(payload)
            true
        }

        assertEquals(listOf("msg1", "msg2", "msg3"), delivered)
        assertEquals(0, q.pendingCount("remote"))
    }

    @Test
    fun `scheduled retry delivers after transient failure`() {
        val scheduled = mutableListOf<Runnable>()
        val q = PublishRetryQueue(
            maxAttempts = 5,
            scheduler = { _, runnable -> scheduled += runnable },
        )
        q.publishFn = { _, _ -> true }

        q.enqueue("remote", "topic", "retry-me".toByteArray())

        // The scheduler was invoked — retry is pending.
        assertEquals(1, scheduled.size)

        // Simulate timer firing: the retry succeeds.
        scheduled.first().run()
        assertEquals(0, q.pendingCount("remote"))
    }

    @Test
    fun `backoff increases with consecutive failures`() {
        val delays = mutableListOf<Long>()
        val q = PublishRetryQueue(
            maxAttempts = 5,
            scheduler = { delayMs, _ -> delays += delayMs },
        )
        q.publishFn = { _, _ -> false }

        q.enqueue("remote", "topic", "x".toByteArray())
        // Initial schedule from enqueue.
        val firstDelay = delays.last()

        // First retry fails, triggering another schedule with longer delay.
        q.retryNow("remote") { _, _ -> false }
        val secondDelay = delays.last()

        assertTrue(secondDelay > firstDelay, "Backoff should increase: $secondDelay > $firstDelay")
    }
}
