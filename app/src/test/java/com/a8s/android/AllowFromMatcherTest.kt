package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AllowFromMatcherTest {

    @Test
    fun `literal matches exact agent only`() {
        val m = AllowFromMatcher("knobert")
        assertTrue(m.matches("knobert"))
        assertFalse(m.matches("knobert-macbook"))
        assertTrue(m.isLiteral)
    }

    @Test
    fun `regex matches full sender name`() {
        val m = AllowFromMatcher("knobert-.*")
        assertFalse(m.matches("knobert"))
        assertTrue(m.matches("knobert-macbook"))
        assertTrue(m.matches("knobert-pi"))
        assertFalse(m.matches("knobertx"))
        assertFalse(m.isLiteral)
    }

    @Test
    fun `hyphenated agent name without metacharacters is literal`() {
        val m = AllowFromMatcher("neil-phone")
        assertTrue(m.isLiteral)
        assertTrue(m.matches("neil-phone"))
        assertFalse(m.matches("neil-phones"))
    }
}
