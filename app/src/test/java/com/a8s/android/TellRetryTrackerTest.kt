package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TellRetryTrackerTest {
    @Test
    fun `one retry success silently clears whole tell`() {
        val tracker = TellRetryTracker()
        tracker.watch("id", "+15551234567", "alice", setOf("one", "two"))
        tracker.accepted("id")

        assertNull(tracker.exhausted("id", "one", 10))
        assertNull(tracker.exhausted("id", "two", 10))
    }

    @Test
    fun `failure appears only after every remote is exhausted`() {
        val tracker = TellRetryTracker()
        tracker.watch("id", "+15551234567", "alice", setOf("one", "two"))

        assertNull(tracker.exhausted("id", "one", 10))
        assertEquals(
            TellRetryTracker.FailureNotice("+15551234567", "alice", 10),
            tracker.exhausted("id", "two", 10),
        )
        assertNull(tracker.exhausted("id", "two", 10))
    }

    @Test
    fun `invalid watch is ignored`() {
        val tracker = TellRetryTracker()
        tracker.watch("", "+15551234567", "alice", setOf("one"))
        assertNull(tracker.exhausted("", "one", 10))
    }

    @Test
    fun `tracker stays bounded`() {
        val tracker = TellRetryTracker(maxTracked = 2)
        tracker.watch("old", "+15551234567", "alice", setOf("one"))
        tracker.watch("middle", "+15551234567", "alice", setOf("one"))
        tracker.watch("new", "+15551234567", "alice", setOf("one"))

        assertNull(tracker.exhausted("old", "one", 10))
        assertEquals(
            TellRetryTracker.FailureNotice("+15551234567", "alice", 10),
            tracker.exhausted("middle", "one", 10),
        )
    }
}
