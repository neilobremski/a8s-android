package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AllowFromMatcherTest {

    @Test
    fun `literal matches exact agent only`() {
        val m = AllowFromMatcher("alice")
        assertTrue(m.matches("alice"))
        assertFalse(m.matches("alice-laptop"))
        assertTrue(m.isLiteral)
    }

    @Test
    fun `regex matches full sender name`() {
        val m = AllowFromMatcher("alice-.*")
        assertFalse(m.matches("alice"))
        assertTrue(m.matches("alice-laptop"))
        assertTrue(m.matches("alice-pi"))
        assertFalse(m.matches("alicex"))
        assertFalse(m.isLiteral)
    }

    @Test
    fun `hyphenated agent name without metacharacters is literal`() {
        val m = AllowFromMatcher("operator-phone")
        assertTrue(m.isLiteral)
        assertTrue(m.matches("operator-phone"))
        assertFalse(m.matches("operator-phones"))
    }
}
