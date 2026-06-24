package com.a8s.android

import org.json.JSONObject

data class EnvelopeFile(val filename: String, val storageUrls: List<String>)

sealed class MqttRoute {
    data class Command(
        val sender: String,
        val name: String,
        val args: List<String>,
        val files: List<EnvelopeFile> = emptyList(),
        val envelopeId: String = "",
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
    return decideRoute(json, config)
}

/** Pre-parsed overload so callers can parse the payload once. */
fun decideRoute(json: JSONObject, config: A8sAndroid.Config): MqttRoute {
    val to = json.optString("to")
    val from = json.optString("from")
    val content = json.optString("content")
    if (isSelfOrigin(from, config)) {
        return MqttRoute.Drop("self-loopback (from=$from)")
    }

    if (to.isEmpty()) {
        return MqttRoute.Drop("missing 'to' field")
    }

    if (to != config.device) {
        return MqttRoute.Drop("to=$to is not this device (${config.device})")
    }

    if (config.registry.principalByAgent(from) == null) {
        return MqttRoute.Drop("from=$from not a configured agent")
    }
    if (content.startsWith("/")) {
        val (name, args) = parseSlashTokens(content)
            ?: return MqttRoute.Drop("empty command from sender=$from")
        if (!config.registry.allowsCommandByAgent(from, name)) {
            return MqttRoute.Drop("from=$from not permitted to run /$name")
        }
        return MqttRoute.Command(from, name, args, parseEnvelopeFiles(json), json.optString("id"))
    }
    return MqttRoute.NotACommand(from)
}

/** Envelope `from` is this device node or a phone-backed agent we publish as. */
fun isSelfOrigin(from: String, config: A8sAndroid.Config): Boolean {
    val f = from.trim()
    if (f.isEmpty()) return false
    if (f == config.device) return true
    return config.registry.isPhoneAgent(f)
}

fun parseSlashTokens(content: String): Pair<String, List<String>>? {
    val tokens = content.removePrefix("/").trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    if (tokens.isEmpty()) return null
    return tokens[0].lowercase() to tokens.drop(1)
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
