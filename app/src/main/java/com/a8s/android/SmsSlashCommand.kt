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

        data class Forbidden(
            val agent: String,
            val verb: String,
        ) : Result()

        object NotForSms : Result()
    }

    fun classify(fromNumber: String, body: String, config: A8sAndroid.Config): Result {
        val trimmed = body.trimStart()
        val effectiveBody = if (trimmed.startsWith("tell ", ignoreCase = true) || trimmed.equals("tell", ignoreCase = true)) {
            "/$trimmed"
        } else {
            trimmed
        }
        if (!effectiveBody.startsWith("/")) return Result.NotForSms
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
}
