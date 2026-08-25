package com.a8s.android

import org.json.JSONArray

/** Human-facing summary when inbound media could not reach public storage. */
object AttachmentFailureAlert {

    fun build(files: JSONArray): String? {
        val failed = buildList {
            for (index in 0 until files.length()) {
                val entry = files.optJSONObject(index) ?: continue
                if (entry.optString("error") != ATTACHMENT_UNAVAILABLE) continue
                add(safeFilename(entry.optString("filename")))
            }
        }
        if (failed.isEmpty()) return null

        val names = failed.take(MAX_NAMES).joinToString(", ")
        val remainder = failed.size - MAX_NAMES
        val suffix = if (remainder > 0) " and $remainder more" else ""
        return "Couldn't upload $names$suffix. The message was forwarded with an " +
            "unavailable-attachment notice; check storage/network and retry."
    }

    private fun safeFilename(filename: String): String =
        filename.replace(Regex("\\s+"), " ").trim().ifEmpty { "attachment" }.take(MAX_NAME_CHARS)

    private const val MAX_NAMES = 3
    private const val MAX_NAME_CHARS = 80
}
