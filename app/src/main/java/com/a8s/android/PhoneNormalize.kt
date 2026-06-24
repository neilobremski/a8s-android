package com.a8s.android

/**
 * Canonical phone-digit normalization for principal phone matching.
 */
object PhoneNormalize {

    const val MIN_SUFFIX_MATCH_DIGITS: Int = 7

    fun normalizePhoneDigits(raw: String): String =
        raw.replace(Regex("[^0-9]"), "")

    fun maskNumber(raw: String): String {
        val digits = normalizePhoneDigits(raw)
        if (digits.length <= 4) return "••••"
        return "••••" + digits.takeLast(4)
    }

    /** Suffix-tolerant match for missing country codes (min digit length enforced). */
    fun phoneDigitsMatch(incoming: String, stored: String): Boolean {
        val a = normalizePhoneDigits(incoming)
        val b = normalizePhoneDigits(stored)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (a.length < MIN_SUFFIX_MATCH_DIGITS || b.length < MIN_SUFFIX_MATCH_DIGITS) return false
        return a.endsWith(b) || b.endsWith(a)
    }
}
