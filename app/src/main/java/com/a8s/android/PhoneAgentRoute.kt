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

    fun tryForward(payload: String, config: A8sAndroid.Config): Forward? {
        val json = try {
            JSONObject(payload)
        } catch (_: org.json.JSONException) {
            return null
        }
        return tryForward(json, config)
    }

    fun tryForward(json: JSONObject, config: A8sAndroid.Config): Forward? {
        val to = json.optString("to").trim()
        if (to.isEmpty()) return null
        val from = json.optString("from").trim()
        if (isSelfOrigin(from, config)) return null
        val phone = config.registry.phoneForAgent(to) ?: return null
        return Forward(
            smsToNumber = phone,
            targetAgent = to,
            from = from,
            content = json.optString("content"),
            files = parseEnvelopeFiles(json),
            envelopeId = json.optString("id"),
        )
    }
}
