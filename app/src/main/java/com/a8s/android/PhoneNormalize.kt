package com.a8s.android

/**
 * Canonical phone-digit normalization for SMS command auth and MQTT
 * sub-identities (issue #38). Digits only — strips `+`, spaces, dashes, etc.
 */
object PhoneNormalize {

    const val DEFAULT_TELL_PREFIX: String = "text"

    fun normalizePhoneDigits(raw: String): String =
        raw.replace(Regex("[^0-9]"), "")

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
        return a == b || a.endsWith(b) || b.endsWith(a)
    }

    /** First phonebook entry whose value matches [incomingNumber]. */
    fun matchPhonebookEntry(
        incomingNumber: String,
        phonebook: Map<String, String>,
    ): Map.Entry<String, String>? =
        phonebook.entries.firstOrNull { (_, number) ->
            phonebookDigitsMatch(incomingNumber, number)
        }

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
