package com.a8s.android

import org.json.JSONObject

/**
 * Inbound MQTT envelopes addressed to an SMS sub-identity (`text-<digits>`).
 * Pure Kotlin for unit tests (issue #38).
 */
object SubIdentityRoute {

    data class Forward(
        val smsToNumber: String,
        val from: String,
        val content: String,
        val files: List<EnvelopeFile>,
        val envelopeId: String,
    )

    fun tryForward(payload: String, config: A8sAndroid.Config): Forward? {
        val json = try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            return null
        }
        return tryForward(json, config)
    }

    /** Pre-parsed overload so the inbound payload is parsed once. */
    fun tryForward(json: JSONObject, config: A8sAndroid.Config): Forward? {
        val to = json.optString("to").trim()
        if (to.isEmpty()) return null
        val from = json.optString("from").trim()
        if (isSelfOrigin(from, config)) return null
        val smsTo = PhoneNormalize.resolveSubIdentityToNumber(to, config.tellPrefix, config.phonebook)
            ?: return null
        return Forward(
            smsToNumber = smsTo,
            from = from,
            content = json.optString("content"),
            files = parseEnvelopeFiles(json),
            envelopeId = json.optString("id"),
        )
    }
}
