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

    if (from !in config.phonebook.keys) {
        return MqttRoute.Drop("from=$from not in phonebook")
    }
    if (content.startsWith("/")) {
        val (name, args) = parseSlashTokens(content)
            ?: return MqttRoute.Drop("empty command from sender=$from")
        return MqttRoute.Command(from, name, args, parseEnvelopeFiles(json), json.optString("id"))
    }
    return MqttRoute.NotACommand(from)
}

/**
 * Single source of truth for "this envelope came from us". Covers both
 * the device participant name and any of our SMS sub-identities — used
 * by [decideRoute] and [SubIdentityRoute] so the loopback rule can't
 * drift between the two paths.
 */
fun isSelfOrigin(from: String, config: A8sAndroid.Config): Boolean {
    val f = from.trim()
    if (f.isEmpty()) return false
    return f == config.device ||
        PhoneNormalize.isOwnSubIdentity(f, config.tellPrefix, config.phonebook)
}

/**
 * Split a `/verb arg arg` string into a lowercased verb + args. Shared
 * by [decideRoute] (MQTT) and [SmsSlashCommand] (SMS) so the parse can't
 * diverge. Returns null when there is no verb.
 */
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
