package com.a8s.android

import org.json.JSONObject

data class EnvelopeFile(val filename: String, val storageUrls: List<String>)

sealed class MqttRoute {
    /**
     * The paired owner has issued a `/command` to the device. `name`
     * is the bare verb (e.g. "info"), `args` is whatever followed split on
     * whitespace. `sender` is the participant name from the envelope's
     * `from` field — force-stamped by the host's a8s router.
     */
    data class Command(
        val sender: String,
        val name: String,
        val args: List<String>,
        val files: List<EnvelopeFile> = emptyList(),
        /** a8s envelope `id` (ULID). Used for idempotent SMS-style commands. */
        val envelopeId: String = "",
        /** When set, command replies go to this number via SMS instead of MQTT. */
        val smsReplyTo: String? = null,
    ) : MqttRoute()
    data class NotACommand(val sender: String) : MqttRoute()
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

    if (to != config.device) {
        return MqttRoute.Drop("to=$to is not this device (${config.device})")
    }

    if (from !in config.phonebook.keys) {
        return MqttRoute.Drop("from=$from not in phonebook")
    }
    if (content.startsWith("/")) {
        val files = parseEnvelopeFiles(json)
        val envelopeId = json.optString("id")
        return parseCommand(from, content, files, envelopeId)
    }
    return MqttRoute.NotACommand(from)
}

private fun parseCommand(
    sender: String,
    content: String,
    files: List<EnvelopeFile> = emptyList(),
    envelopeId: String = "",
): MqttRoute {
    val tokens = content.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) {
        return MqttRoute.Drop("empty command from sender=$sender")
    }
    val name = tokens[0].lowercase()
    val args = tokens.drop(1)
    return MqttRoute.Command(sender, name, args, files, envelopeId)
}

fun parseEnvelopeFiles(json: JSONObject): List<EnvelopeFile> {
    val arr = json.optJSONArray("files") ?: return emptyList()
    val result = mutableListOf<EnvelopeFile>()
    for (i in 0 until arr.length()) {
        val obj = arr.optJSONObject(i) ?: continue
        val filename = obj.optString("filename")
        if (filename.isBlank()) continue
        val storageArr = obj.optJSONArray("storage")
        val urls = mutableListOf<String>()
        if (storageArr != null) {
            for (j in 0 until storageArr.length()) {
                val url = storageArr.optString(j)
                if (url.isNotBlank()) urls += url
            }
        }
        result += EnvelopeFile(filename, urls)
    }
    return result
}
