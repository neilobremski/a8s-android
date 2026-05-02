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

    // Self-loopback. The MQTT broker delivers our own publishes back to
    // every subscriber on the topic, including this device. Without this
    // filter, an SMS reply we just forwarded to MQTT would come back here
    // and we'd try to re-route it as another SMS — at best a noisy drop,
    // at worst (when `to` happens to match a phonebook entry) an infinite
    // SMS loop.
    if (from.isNotEmpty() && from == config.device) {
        return MqttRoute.Drop("self-loopback (from=$from is this device)")
    }

    if (to.isEmpty()) {
        return MqttRoute.Drop("missing 'to' field")
    }

    if (to == config.device) {
        val forward = config.forward
        if (forward.isNullOrBlank()) {
            return MqttRoute.Drop("to=$to is this device but no forward configured")
        }
        // Sender verification: only known cluster participants can reach
        // the operator's phone number via the forward path. The `from`
        // field is force-stamped by the host's a8s router based on the
        // outbox's owning agent, so it's the unforgeable identity we
        // gate on.
        if (from !in config.phonebook.keys) {
            return MqttRoute.Drop("from=$from not in phonebook (sender unauthorized for forward)")
        }
        val smsBody = if (from.isNotEmpty()) "$from: $content" else content
        return MqttRoute.Forward(forward, smsBody)
    }

    val number = config.phonebook[to]
        ?: return MqttRoute.Drop("to=$to not in phonebook and not this device")
    return MqttRoute.Phonebook(to, number, content)
}
