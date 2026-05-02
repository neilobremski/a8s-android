package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UlidTest {
    private val crockford = Regex("^[0-9A-HJKMNP-TV-Z]{26}$")

    @Test
    fun `length is 26 and alphabet is Crockford-base32`() {
        repeat(50) {
            val u = Ulid.new()
            assertEquals(26, u.length)
            assertTrue(crockford.matches(u), "not Crockford-base32: $u")
        }
    }

    @Test
    fun `unique across rapid invocations`() {
        val seen = (1..200).map { Ulid.new() }.toSet()
        assertEquals(200, seen.size)
    }

    @Test
    fun `successive ULIDs in same ms have same time prefix`() {
        // Builder is deterministic given timestamp + entropy; verify that a
        // shared timestamp produces the same first-10 prefix.
        val ts = 1_777_777_777_777L
        val a = Ulid.build(ts, ByteArray(10))
        val b = Ulid.build(ts, ByteArray(10) { 0xFF.toByte() })
        assertEquals(a.substring(0, 10), b.substring(0, 10))
        assertNotEquals(a.substring(10), b.substring(10))
    }

    @Test
    fun `different timestamps produce different time prefixes`() {
        val a = Ulid.build(1_777_777_777_000L, ByteArray(10))
        val b = Ulid.build(1_777_777_778_000L, ByteArray(10))
        assertNotEquals(a.substring(0, 10), b.substring(0, 10))
    }
}
