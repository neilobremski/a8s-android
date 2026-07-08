package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class IngressStalenessTest {

    @Test
    fun `rejects events older than max age`() {
        val now = 100_000_000L
        val event = now - IngressStaleness.NOTIFICATION_MAX_AGE_MS - 1
        assertTrue(
            IngressStaleness.isTooOld(
                event,
                nowMs = now,
                maxAgeMs = IngressStaleness.NOTIFICATION_MAX_AGE_MS,
            ),
        )
    }

    @Test
    fun `allows events within max age`() {
        val now = 100_000_000L
        val event = now - IngressStaleness.NOTIFICATION_MAX_AGE_MS + 1
        assertFalse(
            IngressStaleness.isTooOld(
                event,
                nowMs = now,
                maxAgeMs = IngressStaleness.NOTIFICATION_MAX_AGE_MS,
            ),
        )
    }

    @Test
    fun `unknown timestamp is not stale`() {
        assertFalse(
            IngressStaleness.isTooOld(
                0L,
                nowMs = 1_000_000L,
                maxAgeMs = IngressStaleness.NOTIFICATION_MAX_AGE_MS,
            ),
        )
    }
}
