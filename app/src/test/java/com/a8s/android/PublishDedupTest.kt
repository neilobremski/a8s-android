package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PublishDedupTest {

    @Test
    fun `first publish of a key is allowed`() {
        val d = PublishDedup(windowMs = 1000L)
        assertTrue(d.shouldPublish("a", now = 0L))
    }

    @Test
    fun `same key inside window is rejected`() {
        val d = PublishDedup(windowMs = 1000L)
        assertTrue(d.shouldPublish("a", now = 0L))
        assertFalse(d.shouldPublish("a", now = 500L))
        assertFalse(d.shouldPublish("a", now = 999L))
    }

    @Test
    fun `same key outside window is allowed again`() {
        val d = PublishDedup(windowMs = 1000L)
        assertTrue(d.shouldPublish("a", now = 0L))
        assertTrue(d.shouldPublish("a", now = 1500L))
    }

    @Test
    fun `different keys are independent`() {
        val d = PublishDedup(windowMs = 1000L)
        assertTrue(d.shouldPublish("a", now = 0L))
        assertTrue(d.shouldPublish("b", now = 0L))
        assertFalse(d.shouldPublish("a", now = 100L))
        assertFalse(d.shouldPublish("b", now = 100L))
    }

    @Test
    fun `cache evicts oldest when over maxEntries`() {
        val d = PublishDedup(windowMs = 100_000L, maxEntries = 2)
        assertTrue(d.shouldPublish("a", now = 0L))
        assertTrue(d.shouldPublish("b", now = 1L))
        // Adding c overflows; oldest (a) evicted, {b, c} remain.
        assertTrue(d.shouldPublish("c", now = 2L))
        assertFalse(d.shouldPublish("b", now = 3L), "b should still be tracked")
        assertFalse(d.shouldPublish("c", now = 4L), "c should still be tracked")
        // a was evicted, so re-adding it returns true.
        assertTrue(d.shouldPublish("a", now = 5L), "a should be fresh after eviction")
    }

    @Test
    fun `default window is 5 minutes`() {
        // Documents intent — the constant is part of the contract.
        val d = PublishDedup()
        assertTrue(d.shouldPublish("x", now = 0L))
        assertFalse(d.shouldPublish("x", now = 4 * 60 * 1000L))
        assertTrue(d.shouldPublish("x", now = 5 * 60 * 1000L + 1))
    }
}
