package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneNormalizeTest {

    @Test
    fun `normalizePhoneDigits strips formatting`() {
        assertEquals("13602196756", PhoneNormalize.normalizePhoneDigits("+1 360-219-6756"))
    }

    @Test
    fun `phoneDigitsMatch handles country-code suffix`() {
        assertTrue(PhoneNormalize.phoneDigitsMatch("3602196756", "+13602196756"))
        assertTrue(PhoneNormalize.phoneDigitsMatch("+13602196756", "3602196756"))
    }

    @Test
    fun `phoneDigitsMatch rejects unrelated numbers`() {
        assertFalse(PhoneNormalize.phoneDigitsMatch("15559999999", "+15550001111"))
    }

    @Test
    fun `phoneDigitsMatch refuses short suffix collisions`() {
        assertFalse(PhoneNormalize.phoneDigitsMatch("6756", "+13602196756"))
    }

    @Test
    fun `phoneDigitsMatch still matches identical short numbers`() {
        assertTrue(PhoneNormalize.phoneDigitsMatch("12345", "12345"))
    }

    @Test
    fun `maskNumber hides all but last four digits`() {
        assertEquals("••••6756", PhoneNormalize.maskNumber("+13602196756"))
    }
}
