package com.a8s.android

import org.json.JSONObject
import java.io.File

/** Best-effort bounded-dedup JSON backing store. Callers supply hashed keys. */
class FileDedupStore(private val file: File) {
    fun load(): Map<String, Long> {
        if (!file.isFile) return emptyMap()
        return try {
            val entries = JSONObject(file.readText()).optJSONObject("entries") ?: return emptyMap()
            buildMap {
                val keys = entries.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, entries.getLong(key))
                }
            }
        } catch (_: Exception) {
            emptyMap()
        }
    }

    fun save(entries: Map<String, Long>) {
        try {
            file.parentFile?.mkdirs()
            val values = JSONObject()
            entries.forEach { (key, timestamp) -> values.put(key, timestamp) }
            file.writeText(JSONObject().put("entries", values).toString())
        } catch (_: Exception) {
            // Best effort; in-memory dedup remains active for this process.
        }
    }
}
