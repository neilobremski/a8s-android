package com.a8s.android

/**
 * Classifies inbound SMS/RCS bodies that look like slash commands (#38).
 *
 * Phonebook membership (matched on the sender's number) is the auth
 * gate, and [SmsCommandPolicy] further restricts which verbs may run
 * over this unauthenticated channel.
 */
object SmsSlashCommand {

    sealed class Result {
        /** A permitted command from a known phonebook number. */
        data class Authorized(
            val participantName: String,
            val replyNumber: String,
            val command: MqttRoute.Command,
        ) : Result()

        /** Known phonebook number, but the verb is not allowed over SMS. */
        data class Forbidden(
            val participantName: String,
            val replyNumber: String,
            val verb: String,
        ) : Result()

        /** Not a slash command, or not from a recognized phonebook number. */
        object NotForSms : Result()
    }

    fun classify(fromNumber: String, body: String, config: A8sAndroid.Config): Result {
        if (!body.startsWith("/")) return Result.NotForSms
        val entry = PhoneNormalize.matchPhonebookEntry(fromNumber, config.phonebook)
            ?: return Result.NotForSms
        val (name, args) = parseSlashTokens(body) ?: return Result.NotForSms
        if (!SmsCommandPolicy.isAllowed(name, config.smsAllowedCommands)) {
            return Result.Forbidden(entry.key, entry.value, name)
        }
        return Result.Authorized(
            participantName = entry.key,
            replyNumber = entry.value,
            command = MqttRoute.Command(
                sender = entry.key,
                name = name,
                args = args,
                files = emptyList(),
                envelopeId = "",
                smsReplyTo = entry.value,
            ),
        )
    }
}
