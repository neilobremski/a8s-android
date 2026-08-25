package com.a8s.android

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
    fun `missing pinnedAt decodes as fresh-now and does not repin`() {
        // Tolerant decode: a legacy pin with no stored timestamp is passed in
        // as pinnedAtMs == nowMs (see IncomingSmsRouter.getLastTellPinnedAtMs).
        val now = 5_000_000L
        assertFalse(StickyPin.shouldRepin(nowMs = now, pinnedAtMs = now, ttlMs = 1_800_000L))
    }
}
