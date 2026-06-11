package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneNormalizeTest {

    private val phonebook = mapOf("Neil" to "+1 360-219-6756", "Clover" to "+15550001111")

    @Test
    fun `normalizePhoneDigits strips formatting`() {
        assertEquals("13602196756", PhoneNormalize.normalizePhoneDigits("+1 360-219-6756"))
    }

    @Test
    fun `buildSmsSubIdentity uses prefix and digits`() {
        assertEquals("text-13602196756", PhoneNormalize.buildSmsSubIdentity("text", "+1 360-219-6756"))
    }

    @Test
    fun `buildSmsSubIdentity defaults empty prefix to text`() {
        assertEquals("text-15550001111", PhoneNormalize.buildSmsSubIdentity("", "+15550001111"))
    }

    @Test
    fun `phonebookDigitsMatch handles country-code suffix`() {
        assertTrue(PhoneNormalize.phonebookDigitsMatch("3602196756", "+13602196756"))
        assertTrue(PhoneNormalize.phonebookDigitsMatch("+13602196756", "3602196756"))
    }

    @Test
    fun `phonebookDigitsMatch rejects unrelated numbers`() {
        assertFalse(PhoneNormalize.phonebookDigitsMatch("15559999999", "+15550001111"))
    }

    @Test
    fun `phonebookDigitsMatch refuses short suffix collisions`() {
        // "6756" is below MIN_SUFFIX_MATCH_DIGITS, so it must not match by tail.
        assertFalse(PhoneNormalize.phonebookDigitsMatch("6756", "+13602196756"))
    }

    @Test
    fun `phonebookDigitsMatch still matches identical short numbers`() {
        assertTrue(PhoneNormalize.phonebookDigitsMatch("12345", "12345"))
    }

    @Test
    fun `matchPhonebookEntries returns all matching aliases`() {
        val pb = mapOf("Neil" to "+13602196756", "NeilAlt" to "3602196756", "Other" to "+15550001111")
        val matches = PhoneNormalize.matchPhonebookEntries("13602196756", pb).map { it.key }.toSet()
        assertEquals(setOf("Neil", "NeilAlt"), matches)
    }

    @Test
    fun `maskNumber hides all but last four`() {
        assertEquals("••••6756", PhoneNormalize.maskNumber("+1 360-219-6756"))
        assertEquals("••••", PhoneNormalize.maskNumber("123"))
    }

    @Test
    fun `matchPhonebookEntry finds first matching value`() {
        val entry = PhoneNormalize.matchPhonebookEntry("3602196756", phonebook)
        assertEquals("Neil", entry?.key)
        assertEquals("+1 360-219-6756", entry?.value)
    }

    @Test
    fun `resolveSubIdentityToNumber maps sub-identity back to stored number`() {
        val number = PhoneNormalize.resolveSubIdentityToNumber("text-13602196756", "text", phonebook)
        assertEquals("+1 360-219-6756", number)
    }

    @Test
    fun `resolveSubIdentityToNumber returns null for unknown identity`() {
        assertNull(PhoneNormalize.resolveSubIdentityToNumber("text-19999999999", "text", phonebook))
    }

    @Test
    fun `isOwnSubIdentity detects loopback from sub-identity`() {
        assertTrue(PhoneNormalize.isOwnSubIdentity("text-13602196756", "text", phonebook))
        assertFalse(PhoneNormalize.isOwnSubIdentity("Bob", "text", phonebook))
    }
}
