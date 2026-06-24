package com.a8s.android

import org.json.JSONObject

/**
 * Inbound MQTT envelopes addressed to a phone-backed agent (e.g. `neil-phone`).
 * Content is forwarded to SMS **opaquely** — even `/logs` is not executed locally.
 */
object PhoneAgentRoute {

    data class Forward(
        val smsToNumber: String,
        val targetAgent: String,
        val from: String,
        val content: String,
        val files: List<EnvelopeFile>,
        val envelopeId: String,
    )

    sealed class Result {
        data class Ok(val forward: Forward) : Result()
        /** `to` is a phone agent but `from` is not on that principal's allow list. */
        data class Denied(val targetAgent: String, val from: String) : Result()
        /** Not addressed to a phone-backed agent (or self-loopback). */
        object NotApplicable : Result()
    }

    fun tryForward(payload: String, config: A8sAndroid.Config): Forward? =
        when (val r = evaluate(payload, config)) {
            is Result.Ok -> r.forward
            else -> null
        }

    fun evaluate(payload: String, config: A8sAndroid.Config): Result {
        val json = try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            return Result.NotApplicable
        }
        return evaluate(json, config)
    }

    fun evaluate(json: JSONObject, config: A8sAndroid.Config): Result {
        val to = json.optString("to").trim()
        if (to.isEmpty()) return Result.NotApplicable
        val from = json.optString("from").trim()
        if (isSelfOrigin(from, config)) return Result.NotApplicable
        val phone = config.registry.phoneForAgent(to) ?: return Result.NotApplicable
        if (!config.registry.allowsPhoneForward(from, to)) {
            return Result.Denied(targetAgent = to, from = from)
        }
        return Result.Ok(
            Forward(
                smsToNumber = phone,
                targetAgent = to,
                from = from,
                content = json.optString("content"),
                files = parseEnvelopeFiles(json),
                envelopeId = json.optString("id"),
            ),
        )
    }
}
