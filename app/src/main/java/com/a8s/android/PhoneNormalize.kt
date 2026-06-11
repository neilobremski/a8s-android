package com.a8s.android

/**
 * Canonical phone-digit normalization for SMS command auth and MQTT
 * sub-identities (issue #38). Digits only — strips `+`, spaces, dashes, etc.
 */
object PhoneNormalize {

    const val DEFAULT_TELL_PREFIX: String = "text"

    /**
     * Minimum digit count before a suffix match is allowed. Stops short
     * numbers / short-codes from matching a phonebook entry by tail
     * coincidence — important because phonebook membership is the SMS
     * auth gate.
     */
    const val MIN_SUFFIX_MATCH_DIGITS: Int = 7

    fun normalizePhoneDigits(raw: String): String =
        raw.replace(Regex("[^0-9]"), "")

    /** Mask all but the last 4 digits for logging (e.g. `••••6756`). */
    fun maskNumber(raw: String): String {
        val digits = normalizePhoneDigits(raw)
        if (digits.length <= 4) return "••••"
        return "••••" + digits.takeLast(4)
    }

    /** `text-13602196756` from prefix `text` and `+1 360-219-6756`. */
    fun buildSmsSubIdentity(prefix: String, phoneNumber: String): String {
        val p = prefix.trim().ifEmpty { DEFAULT_TELL_PREFIX }
        val digits = normalizePhoneDigits(phoneNumber)
        require(digits.isNotEmpty()) { "phone number has no digits" }
        return "$p-$digits"
    }

    /**
     * Match inbound sender to a configured phonebook value. Uses suffix
     * matching so `3602196756` matches `+13602196756` (same as reply-cache).
     */
    fun phonebookDigitsMatch(incoming: String, stored: String): Boolean {
        val a = normalizePhoneDigits(incoming)
        val b = normalizePhoneDigits(stored)
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        // Suffix match tolerates a missing country code, but only for
        // numbers long enough that a tail collision is implausible.
        if (a.length < MIN_SUFFIX_MATCH_DIGITS || b.length < MIN_SUFFIX_MATCH_DIGITS) return false
        return a.endsWith(b) || b.endsWith(a)
    }

    /** Every phonebook entry whose value matches [incomingNumber]. */
    fun matchPhonebookEntries(
        incomingNumber: String,
        phonebook: Map<String, String>,
    ): List<Map.Entry<String, String>> =
        phonebook.entries.filter { (_, number) ->
            phonebookDigitsMatch(incomingNumber, number)
        }

    /** First phonebook entry whose value matches [incomingNumber]. */
    fun matchPhonebookEntry(
        incomingNumber: String,
        phonebook: Map<String, String>,
    ): Map.Entry<String, String>? =
        matchPhonebookEntries(incomingNumber, phonebook).firstOrNull()

    /**
     * If [participantTo] equals `{prefix}-{digits}` for a phonebook value,
     * return that value's stored number (for SMS delivery).
     */
    fun resolveSubIdentityToNumber(
        participantTo: String,
        prefix: String,
        phonebook: Map<String, String>,
    ): String? {
        val p = prefix.trim().ifEmpty { DEFAULT_TELL_PREFIX }
        val needle = participantTo.trim()
        for ((_, number) in phonebook) {
            if (buildSmsSubIdentity(p, number) == needle) {
                return number
            }
        }
        return null
    }

    fun isOwnSubIdentity(
        participantFrom: String,
        prefix: String,
        phonebook: Map<String, String>,
    ): Boolean = phonebook.values.any { number ->
        buildSmsSubIdentity(prefix, number) == participantFrom.trim()
    }
}
