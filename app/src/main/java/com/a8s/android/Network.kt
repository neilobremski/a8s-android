package com.a8s.android

import org.json.JSONObject

/**
 * JSON → typed remotes + services. Pure Kotlin (no Android dependencies)
 * so it stays unit-testable.
 *
 * The new shape (1.10.0+) matches `apps/a8s/network.json` upstream:
 * ```
 * "remotes":  { "<name>": { "transport": "mqtt", "broker": "...",
 *                            "topic": "...", "username": "...",
 *                            "password": "..." } },
 * "services": { "<name>": { "service": "tempfile_org",
 *                            "url": "...",
 *                            "expiry_hours": 24 } }
 * ```
 *
 * Legacy shape (≤ 1.9.0) had a singular flat `remote` object. We accept
 * it as an alias and wrap it as `remotes: { "default": ... }` so an
 * in-place upgrade doesn't require the user to rewrite the config
 * file before tapping `/update`.
 */
object Network {

    // Reserved keys the dispatcher consumes itself; everything else
    // forwards to the per-transport / per-service constructor as opts.
    private val REMOTE_RESERVED = setOf("transport", "broker", "url", "topic")
    private val SERVICE_RESERVED = setOf("service", "url")

    /** Parse the `remotes` map (new) or wrap a legacy `remote` block.
     *  Returns insertion-ordered map keyed by remote name. Throws on
     *  shape errors that prevent constructing any remote (caller's
     *  caller logs and continues). */
    fun parseRemotes(root: JSONObject): Map<String, RemoteConfig> {
        val out = linkedMapOf<String, RemoteConfig>()
        val remotesObj = root.optJSONObject("remotes")
        val singleObj = root.optJSONObject("remote")
        when {
            remotesObj != null -> {
                val keys = remotesObj.keys()
                while (keys.hasNext()) {
                    val name = keys.next()
                    val spec = remotesObj.optJSONObject(name) ?: continue
                    parseRemoteSpec(spec)?.let { out[name] = it }
                }
            }
            singleObj != null -> {
                parseRemoteSpec(singleObj)?.let { out["default"] = it }
            }
        }
        return out
    }

    /** Parse the `services` map into typed [StorageService]s. Unknown
     *  service kinds throw; callers should report the issue (and let
     *  the rest of the config load). */
    fun parseServices(root: JSONObject): List<StorageService> {
        val obj = root.optJSONObject("services") ?: return emptyList()
        val out = mutableListOf<StorageService>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val name = keys.next()
            val spec = obj.optJSONObject(name) ?: continue
            out += buildService(name, spec)
        }
        return out
    }

    private fun parseRemoteSpec(spec: JSONObject): RemoteConfig? {
        // Accept both the canonical `broker` (Python) and legacy `url`
        // (the old singular shape). One of them is required.
        val broker = spec.optString("broker").ifBlank { spec.optString("url") }
        if (broker.isBlank()) return null
        val topic = spec.optString("topic")
        if (topic.isBlank()) return null
        val transport = spec.optString("transport").ifBlank { "mqtt" }
        // `user`/`pass` are aliased forms accepted by the Python config
        // dispatcher; honor them too.
        val username = spec.optString("username")
            .ifBlank { spec.optString("user") }
            .ifBlank { null }
        val password = spec.optString("password")
            .ifBlank { spec.optString("pass") }
            .ifBlank { null }
        // Reject unknown keys so a typo in the JSON fails loud rather
        // than silently doing nothing.
        rejectUnknownKeys(spec, REMOTE_RESERVED + setOf("username", "user", "password", "pass"))
        return RemoteConfig(
            transport = transport,
            broker = broker,
            topic = topic,
            username = username,
            password = password,
        )
    }

    private fun buildService(name: String, spec: JSONObject): StorageService {
        val kind = spec.optString("service").trim().lowercase()
        val url = spec.optString("url")
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
