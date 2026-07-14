package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandDedupTest {

    @Test
    fun `first envelope id is allowed`() {
        val d = CommandDedup(windowMs = 60_000L)
        assertTrue(d.shouldExecute("01KTVEBX9HA8ZHMH5BP2EA38WQ", null, now = 0L))
    }

    @Test
    fun `same envelope id inside window is rejected`() {
        val d = CommandDedup(windowMs = 60_000L)
        val id = "01KTVEBX9HA8ZHMH5BP2EA38WQ"
        assertTrue(d.shouldExecute(id, null, now = 0L))
        assertFalse(d.shouldExecute(id, null, now = 1_000L))
    }

    @Test
    fun `same payload key with different envelope id is rejected`() {
        val d = CommandDedup(windowMs = 60_000L)
        val key = "send|+15550001111|Morning Ritual complete"
        assertTrue(d.shouldExecute("01KTVEBX9HA8ZHMH5BP2EA38WQ", key, now = 0L))
        assertFalse(d.shouldExecute("01KTVEC65041382PWQZW785YCK", key, now = 500L))
    }

    @Test
    fun `different payload keys are independent`() {
        val d = CommandDedup(windowMs = 60_000L)
        assertTrue(d.shouldExecute(null, "send|+1|hello", now = 0L))
        assertTrue(d.shouldExecute(null, "send|+1|goodbye", now = 0L))
    }

    @Test
    fun `payload expires after window`() {
        val d = CommandDedup(windowMs = 1_000L)
        val key = "reply|+15550001111|ok"
        assertTrue(d.shouldExecute(null, key, now = 0L))
        assertFalse(d.shouldExecute(null, key, now = 500L))
        assertTrue(d.shouldExecute(null, key, now = 1_500L))
    }

}
