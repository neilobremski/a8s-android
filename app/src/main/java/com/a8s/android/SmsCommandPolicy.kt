package com.a8s.android

/**
 * Allow-list policy for slash commands arriving over the SMS/RCS channel (#38).
 *
 * SMS/RCS is an unauthenticated channel: the originating address is
 * spoofable (raw SMS) and the RCS path only gives us a contact display
 * name resolved through Contacts. Unlike MQTT — where `from` is
 * host-stamped over TLS — we cannot treat the sender as trusted. So the
 * SMS command surface is restricted to a safe-by-default subset and the
 * operator must explicitly opt into the destructive verbs.
 *
 * This gate applies ONLY to SMS/RCS-originated commands. Commands that
 * arrive over MQTT keep the full surface.
 */
object SmsCommandPolicy {

    /** Sentinel allowing every verb. Set `allowed_commands: ["*"]` to opt in. */
    const val WILDCARD: String = "*"

    /**
     * Observe / notify / capture verbs only. Deliberately excludes:
     * `update` (installs arbitrary APKs), `rm`/`cat` (filesystem
     * delete / exfiltration), `send`/`mms`/`reply` (sends SMS as the
     * victim), and the UI-automation verbs `macro`/`tap`/`longtap`/
     * `swipe`/`key`/`input` (can do anything on the device).
     */
    val DEFAULT_ALLOWED: Set<String> = setOf(
        "info",
        "logs",
        "trace",
        "location",
        "say",
        "notify",
        "ls",
        "find",
        "dashboard",
        "photo",
        "video",
        "audio",
        "screenshot",
        "tell",
    )

    fun isAllowed(verb: String, allowed: Set<String>): Boolean =
        allowed.contains(WILDCARD) || allowed.contains(verb.lowercase())
}
