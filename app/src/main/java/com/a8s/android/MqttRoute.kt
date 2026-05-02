package com.a8s.android

import org.json.JSONObject

sealed class MqttRoute {
    data class Forward(val number: String, val smsBody: String) : MqttRoute()
    data class Phonebook(val name: String, val number: String, val smsBody: String) : MqttRoute()
    /**
     * A phonebook-known sender has issued a `/command` to the device. `name`
     * is the bare verb (e.g. "info"), `args` is whatever followed split on
     * whitespace. `sender` is the participant name from the envelope's
     * `from` field — force-stamped by the host's a8s router and gated here
     * by phonebook membership; the executor doesn't re-check.
     */
    data class Command(val sender: String, val name: String, val args: List<String>) : MqttRoute()
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

    if (from.isNotEmpty() && from == config.device) {
        return MqttRoute.Drop("self-loopback (from=$from is this device)")
    }

    if (to.isEmpty()) {
        return MqttRoute.Drop("missing 'to' field")
    }

    if (to == config.device) {
        if (from !in config.phonebook.keys) {
            return MqttRoute.Drop("from=$from not in phonebook")
        }
        if (content.startsWith("/")) {
            return parseCommand(from, content)
        }
        val number = config.phonebook[from]
            ?: return MqttRoute.Drop("from=$from not in phonebook")
        return MqttRoute.Forward(number, content)
    }

    val number = config.phonebook[to]
        ?: return MqttRoute.Drop("to=$to not in phonebook and not this device")
    return MqttRoute.Phonebook(to, number, content)
}

private fun parseCommand(sender: String, content: String): MqttRoute {
    val tokens = content.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return MqttRoute.Drop("empty command from sender=$sender")
    }
    val name = tokens[0].lowercase()
    val args = tokens.drop(1)
    return MqttRoute.Command(sender, name, args)
}
