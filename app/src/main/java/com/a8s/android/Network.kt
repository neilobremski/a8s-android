package com.a8s.android

import org.json.JSONObject

/**
 * JSON → typed remotes + services. Pure Kotlin (no Android dependencies).
 */
object Network {

    private val REMOTE_RESERVED = setOf("transport", "broker", "topic")
    private val SERVICE_RESERVED = setOf("service", "url")

    fun parseRemotes(root: JSONObject): Map<String, RemoteConfig> {
        val out = linkedMapOf<String, RemoteConfig>()
        val remotesObj = root.getJSONObject("remotes")
        val keys = remotesObj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val spec = remotesObj.getJSONObject(name)
            parseRemoteSpec(spec)?.let { out[name] = it }
        }
        return out
    }

    fun parseServices(root: JSONObject): List<StorageService> {
        val obj = root.optJSONObject("services") ?: return emptyList()
        val out = mutableListOf<StorageService>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val spec = obj.getJSONObject(name)
            out += buildService(name, spec)
        }
        return out
    }

    private fun parseRemoteSpec(spec: JSONObject): RemoteConfig? {
        val broker = spec.getString("broker")
        if (broker.isBlank()) return null
        val topic = spec.getString("topic")
        if (topic.isBlank()) return null
        val transport = spec.optString("transport").ifBlank { "mqtt" }
        val username = spec.optString("username").ifBlank { null }
        val password = spec.optString("password").ifBlank { null }
        rejectUnknownKeys(spec, REMOTE_RESERVED + setOf("username", "password"))
        return RemoteConfig(
            transport = transport,
            broker = broker,
            topic = topic,
            username = username,
            password = password,
        )
    }

    private fun buildService(name: String, spec: JSONObject): StorageService {
        val kind = spec.getString("service").trim().lowercase()
        val url = spec.getString("url")
        require(url.isNotBlank()) { "storage $name: missing 'url'" }
        return when (kind) {
            "tempfile_org" -> {
                val expiryHours = spec.optInt("expiry_hours", TempFileOrgService.DEFAULT_EXPIRY_HOURS)
                val timeoutS = spec.optInt("timeout_s", TempFileOrgService.DEFAULT_TIMEOUT_S)
                rejectUnknownKeys(spec, SERVICE_RESERVED + setOf("expiry_hours", "timeout_s"))
                TempFileOrgService(name, url, expiryHours = expiryHours, timeoutS = timeoutS)
            }
            else -> throw IllegalArgumentException(
                "storage $name: unsupported service kind '$kind' (known: tempfile_org)",
            )
        }
    }

    private fun rejectUnknownKeys(spec: JSONObject, allowed: Set<String>) {
        val keys = spec.keys()
        val unknown = mutableListOf<String>()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k !in allowed) unknown += k
        }
        require(unknown.isEmpty()) { "unknown config key(s): ${unknown.sorted()}" }
    }
}
