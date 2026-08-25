package com.a8s.android

/**
 * Inbound SMS/RCS bodies that are slash commands from phone principals.
 */
object SmsSlashCommand {

    sealed class Result {
        data class Authorized(
            val principal: Principal,
            val replyNumber: String,
            val command: MqttRoute.Command,
        ) : Result()

        /**
         * A `hey`/`ok`/`okay` body that passed the `tell` permission gate. The
         * router must still resolve [command]'s target before dispatching it —
         * an unresolvable target means this was conversational text, not a
         * command, and the router falls through to sticky routing instead.
         */
        data class ConversationalTell(
            val principal: Principal,
            val replyNumber: String,
            val command: MqttRoute.Command,
        ) : Result()

        data class Forbidden(
            val agent: String,
            val verb: String,
        ) : Result()

        object NotForSms : Result()
    }

    private val CONVERSATIONAL_PREFIXES = listOf("hey", "ok", "okay")

    fun classify(fromNumber: String, body: String, config: A8sAndroid.Config): Result {
        val trimmed = body.trimStart()
        val effectiveBody = if (trimmed.startsWith("tell ", ignoreCase = true) || trimmed.equals("tell", ignoreCase = true)) {
            "/$trimmed"
        } else {
            trimmed
        }
        if (effectiveBody.startsWith("/")) {
            return classifySlash(fromNumber, effectiveBody, config)
        }
        val rest = conversationalRest(trimmed) ?: return Result.NotForSms
        return classifyConversational(fromNumber, rest, config)
    }

    private fun classifySlash(fromNumber: String, effectiveBody: String, config: A8sAndroid.Config): Result {
        val principal = config.registry.principalByPhone(fromNumber) ?: return Result.NotForSms
        val phone = principal.phone ?: return Result.NotForSms
        val (name, args) = parseSlashTokens(effectiveBody) ?: return Result.NotForSms
        if (!config.registry.allowsCommand(principal, name)) {
            return Result.Forbidden(principal.agent, name)
        }
        return Result.Authorized(
            principal = principal,
            replyNumber = phone,
            command = MqttRoute.Command(
                sender = principal.agent,
                name = name,
                args = args,
                files = emptyList(),
                envelopeId = "",
                smsReplyTo = phone,
            ),
        )
    }

    private fun classifyConversational(fromNumber: String, rest: String, config: A8sAndroid.Config): Result {
        val principal = config.registry.principalByPhone(fromNumber) ?: return Result.NotForSms
        val phone = principal.phone ?: return Result.NotForSms
        // Without the tell permission a conversational prefix is just
        // conversation — fall through to sticky routing, never drop it the
        // way an explicit /tell attempt is dropped.
        if (!config.registry.allowsCommand(principal, "tell")) {
            return Result.NotForSms
        }
        val args = rest.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        return Result.ConversationalTell(
            principal = principal,
            replyNumber = phone,
            command = MqttRoute.Command(
                sender = principal.agent,
                name = "tell",
                args = args,
                files = emptyList(),
                envelopeId = "",
                smsReplyTo = phone,
            ),
        )
    }

    /** Strips a `hey`/`ok`/`okay` prefix word; null when [trimmed] carries none. */
    internal fun conversationalRest(trimmed: String): String? {
        for (prefix in CONVERSATIONAL_PREFIXES) {
            if (trimmed.startsWith("$prefix ", ignoreCase = true)) return trimmed.substring(prefix.length + 1)
            if (trimmed.equals(prefix, ignoreCase = true)) return ""
        }
        return null
    }
}
