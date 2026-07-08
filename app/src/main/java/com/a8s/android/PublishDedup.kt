package com.a8s.android

import org.json.JSONObject
import java.io.File

/**
 * Bounded-window dedup cache for outbound MQTT publishes.
 *
 * Two layers:
 * 1. In-memory ring (default 5 minutes) — rapid duplicates from Google
 *    Messages re-posting notifications or SMS + notification double delivery.
 * 2. Optional persisted ring (default 7 days) — survives process restarts
 *    and rejects the same `from|to|body` replayed days later.
 *
 * Pure Kotlin (no Android deps) so it stays unit-testable.
 */
class PublishDedup(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val persistWindowMs: Long = DEFAULT_PERSIST_WINDOW_MS,
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
    private val maxPersistEntries: Int = DEFAULT_PERSIST_MAX_ENTRIES,
    private val store: FileDedupStore? = null,
) {
    private val seen = mutableMapOf<String, Long>()
    private val persisted = mutableMapOf<String, Long>()
    private val persistEnabled: Boolean = store != null

    init {
        if (persistEnabled) {
            store?.load()?.let { persisted.putAll(it) }
        }
    }

    @Synchronized
    fun shouldPublish(key: String, now: Long = System.currentTimeMillis()): Boolean {
        evictExpired(seen, now, windowMs)
        if (persistEnabled) {
            evictExpired(persisted, now, persistWindowMs)
        }

        if (key in seen) return false
        if (persistEnabled && key in persisted) return false

        seen[key] = now
        if (persistEnabled) {
            persisted[key] = now
            boundSize(persisted, maxPersistEntries)
            store?.save(persisted)
        }
        boundSize(seen, maxEntries)
        return true
    }

    private fun evictExpired(map: MutableMap<String, Long>, now: Long, window: Long) {
        val cutoff = now - window
        map.entries.filter { it.value < cutoff }.map { it.key }.forEach { map.remove(it) }
    }

    private fun boundSize(map: MutableMap<String, Long>, cap: Int) {
        while (map.size > cap) {
            val oldest = map.entries.minByOrNull { it.value } ?: break
            map.remove(oldest.key)
        }
    }

    companion object {
        const val DEFAULT_WINDOW_MS: Long = 5L * 60L * 1000L
        const val DEFAULT_PERSIST_WINDOW_MS: Long = 7L * 24L * 60L * 60L * 1000L
        const val DEFAULT_MAX_ENTRIES: Int = 100
        const val DEFAULT_PERSIST_MAX_ENTRIES: Int = 2000
    }
}

/** JSON file backing store for [PublishDedup] persisted keys. */
class FileDedupStore(private val file: File) {

    fun load(): Map<String, Long> {
        if (!file.isFile) return emptyMap()
        return try {
            val root = JSONObject(file.readText())
            val entries = root.optJSONObject("entries") ?: return emptyMap()
            buildMap {
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    put(k, entries.getLong(k))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(entries: Map<String, Long>) {
        try {
            file.parentFile?.mkdirs()
            val obj = JSONObject()
            val map = JSONObject()
            for ((k, v) in entries) {
                map.put(k, v)
            }
            obj.put("entries", map)
            file.writeText(obj.toString())
        } catch (_: Exception) {
            // Best-effort — in-memory layer still protects short window.
        }
    }
}
