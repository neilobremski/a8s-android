package com.a8s.android

import org.json.JSONObject

sealed class MqttRoute {
    data class Forward(val number: String, val smsBody: String) : MqttRoute()
    data class Phonebook(val name: String, val number: String, val smsBody: String) : MqttRoute()
    data class Drop(val reason: String) : MqttRoute()
    data class ParseError(val reason: String) : MqttRoute()
}

fun decideRoute(payload: String, config: A8sAndroid.Config): MqttRoute {
    val json = try {
        JSONObject(payload)
    } catch (e: org.json.JSONException) {
        return MqttRoute.ParseError(e.message ?: "invalid JSON")
    }
    val to = json.optString("to")
    val from = json.optString("from")
    val content = json.optString("content")

    if (to.isEmpty()) {
        return MqttRoute.Drop("missing 'to' field")
    }

    if (to == config.device) {
        val forward = config.forward
        if (forward.isNullOrBlank()) {
            return MqttRoute.Drop("to=$to is this device but no forward configured")
        }
        val smsBody = if (from.isNotEmpty()) "$from: $content" else content
        return MqttRoute.Forward(forward, smsBody)
    }

    val number = config.phonebook[to]
        ?: return MqttRoute.Drop("to=$to not in phonebook and not this device")
    return MqttRoute.Phonebook(to, number, content)
}
