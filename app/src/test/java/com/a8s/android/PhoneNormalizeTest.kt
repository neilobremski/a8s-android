package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneNormalizeTest {

    @Test
    fun `normalizePhoneDigits strips formatting`() {
        assertEquals("15551234567", PhoneNormalize.normalizePhoneDigits("+1 555-123-4567"))
    }

    @Test
    fun `phoneDigitsMatch handles country-code suffix`() {
        assertTrue(PhoneNormalize.phoneDigitsMatch("5551234567", "+15551234567"))
        assertTrue(PhoneNormalize.phoneDigitsMatch("+15551234567", "5551234567"))
    }

    @Test
    fun `phoneDigitsMatch rejects unrelated numbers`() {
        assertFalse(PhoneNormalize.phoneDigitsMatch("15559999999", "+15550001111"))
    }

    @Test
    fun `phoneDigitsMatch refuses short suffix collisions`() {
        assertFalse(PhoneNormalize.phoneDigitsMatch("4567", "+15551234567"))
    }

    @Test
    fun `phoneDigitsMatch still matches identical short numbers`() {
        assertTrue(PhoneNormalize.phoneDigitsMatch("12345", "12345"))
    }

    @Test
    fun `maskNumber hides all but last four digits`() {
        assertEquals("••••4567", PhoneNormalize.maskNumber("+15551234567"))
    }
}
