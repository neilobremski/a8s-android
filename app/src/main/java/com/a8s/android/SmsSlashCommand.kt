package com.a8s.android

/**
 * Inbound SMS/RCS bodies that are slash commands from phonebook numbers (#38).
 */
object SmsSlashCommand {

    data class Authorized(
        /** Phonebook participant name (for logging / MQTT-style sender field). */
        val participantName: String,
        /** Raw phonebook value — SMS reply destination. */
        val replyNumber: String,
        val command: MqttRoute.Command,
    )

    /**
     * @return authorized command, or null if this is not an SMS slash command.
     */
    fun tryParse(fromNumber: String, body: String, config: A8sAndroid.Config): Authorized? {
        if (!body.startsWith("/")) return null
        val entry = PhoneNormalize.matchPhonebookEntry(fromNumber, config.phonebook) ?: return null
        val parsed = parseSlashContent(body) ?: return null
        return Authorized(
            participantName = entry.key,
            replyNumber = entry.value,
            command = MqttRoute.Command(
                sender = entry.key,
                name = parsed.name,
                args = parsed.args,
                files = emptyList(),
                envelopeId = "",
                smsReplyTo = entry.value,
            ),
        )
    }

    data class ParsedSlash(val name: String, val args: List<String>)

    fun parseSlashContent(content: String): ParsedSlash? {
        val tokens = content.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        return ParsedSlash(tokens[0].lowercase(), tokens.drop(1))
    }
}
