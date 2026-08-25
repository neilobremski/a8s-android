package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StickyPinTest {

    @Test
    fun `fresh pin does not repin`() {
        assertFalse(StickyPin.shouldRepin(nowMs = 1_000_000L, pinnedAtMs = 999_000L, ttlMs = 1_800_000L))
    }

    @Test
    fun `stale pin repins`() {
        assertTrue(StickyPin.shouldRepin(nowMs = 2_000_000L, pinnedAtMs = 0L, ttlMs = 1_800_000L))
    }

    @Test
    fun `age exactly at ttl counts as stale`() {
        assertTrue(StickyPin.shouldRepin(nowMs = 1_800_000L, pinnedAtMs = 0L, ttlMs = 1_800_000L))
    }

    @Test
    fun `ttl zero never repins regardless of age`() {
        assertFalse(StickyPin.shouldRepin(nowMs = Long.MAX_VALUE, pinnedAtMs = 0L, ttlMs = 0L))
    }

    @Test
    fun `decide with no pin adopts the candidate`() {
        assertEquals(
            StickyPin.Decision.PIN_FIRST,
            StickyPin.decide(currentTarget = null, pinnedAtMs = null, nowMs = 1_000L, ttlMs = 1_800_000L),
        )
    }

    @Test
    fun `decide stamps a legacy pin instead of keeping it immortal or stealing it`() {
        // A pre-timestamp pin is stamped once, kept, and ages from there —
        // it is neither treated as forever-fresh nor stolen on first sight.
        assertEquals(
            StickyPin.Decision.STAMP_AND_KEEP,
            StickyPin.decide(currentTarget = "alice", pinnedAtMs = null, nowMs = 5_000_000L, ttlMs = 1_800_000L),
        )
    }

    @Test
    fun `legacy pin ages normally after its migration stamp`() {
        val stampedAt = 5_000_000L
        val ttl = 1_800_000L
        assertEquals(
            StickyPin.Decision.KEEP,
            StickyPin.decide("alice", stampedAt, stampedAt + ttl - 1, ttl),
        )
        assertEquals(
            StickyPin.Decision.REPIN,
            StickyPin.decide("alice", stampedAt, stampedAt + ttl, ttl),
        )
    }

    @Test
    fun `decide keeps a fresh pin and repins a stale one`() {
        val ttl = 1_800_000L
        assertEquals(StickyPin.Decision.KEEP, StickyPin.decide("alice", 999_000L, 1_000_000L, ttl))
        assertEquals(StickyPin.Decision.REPIN, StickyPin.decide("alice", 0L, 2_000_000L, ttl))
    }

    @Test
    fun `decide with ttl zero keeps an ancient pin`() {
        assertEquals(
            StickyPin.Decision.KEEP,
            StickyPin.decide("alice", 0L, Long.MAX_VALUE, ttlMs = 0L),
        )
    }
}
