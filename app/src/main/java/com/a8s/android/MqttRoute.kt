package com.a8s.android

import org.json.JSONObject

sealed class MqttRoute {
    data class Forward(val number: String, val smsBody: String) : MqttRoute()
    data class Phonebook(val name: String, val number: String, val smsBody: String) : MqttRoute()
    /**
     * The owner has issued a `/command` to the device. `name` is the bare
     * verb (e.g. "info"), `args` is whatever followed split on whitespace.
     * Owner authorization is established at routing time (this branch is
     * only chosen when `from == config.owner`); the executor doesn't
     * re-check.
     */
    data class Command(val owner: String, val name: String, val args: List<String>) : MqttRoute()
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
        // Owner-issued slash command. The owner is the unforgeable identity
        // for privileged on-device actions; the host's a8s router force-
        // stamps `from` so we can trust it. Bypasses the phonebook gate
        // that the Forward path uses — owner authorization is the gate.
        val owner = config.owner
        if (!owner.isNullOrBlank() && from == owner && content.startsWith("/")) {
            return parseCommand(owner, content)
        }
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

private fun parseCommand(owner: String, content: String): MqttRoute {
    // content begins with "/" by contract. Strip it, split on whitespace,
    // first token is the verb (lowercased for canonicalization), the rest
    // are positional args. Empty verb => Drop with a clear reason rather
    // than handing an empty Command to the executor.
    val tokens = content.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return MqttRoute.Drop("empty command from owner=$owner")
    }
    val name = tokens[0].lowercase()
    val args = tokens.drop(1)
    return MqttRoute.Command(owner, name, args)
}
