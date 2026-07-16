package com.a8s.android

import java.util.Locale

/**
 * Pure-Kotlin helpers for the device-capability slash commands
 * (`/photo`, `/video`, `/location`, `/say`, `/notify`, `/ls`, `/cat`,
 * `/rm`). Argument parsing and response formatting live here so they
 * can be unit-tested without an Android Context — same pattern as
 * `Commands.renderInfo` taking an `InfoSnapshot`.
 *
 * Anything that needs Camera2, MediaRecorder, LocationManager,
 * TextToSpeech, NotificationManager, or filesystem IO lives in the
 * matching `Cmd<Name>.kt` and is invoked from `A8sService`.
 */
object CmdHelpers {

    /**
     * Upper bound on the text body of an SMS reply / forward. Command
     * output (`/info` verbose, `/logs`, `/ls`, `/cat`) is otherwise
     * unbounded and would fan out into many billable SMS segments and
     * risk carrier truncation. ~8 segments of GSM-7.
     */
    const val MAX_SMS_REPLY_CHARS: Int = 160

    /** Split [text] into chunks matching the configured limit. */
    fun chunkForSms(text: String, config: A8sAndroid.Config? = A8sAndroid.config): List<String> {
        val max = config?.smsTruncateLimit ?: 800
        if (max <= 0 || text.length <= max) return listOf(text)

        // Calculate chunk size leaving room for prefix "part X of Y: " (approx 15 chars max)
        val prefixReserve = 20
        val chunkSize = (max - prefixReserve).coerceAtLeast(10)
        
        val totalChunks = (text.length + chunkSize - 1) / chunkSize
        val chunks = mutableListOf<String>()
        
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, text.length)
            val chunkText = text.substring(start, end)
            chunks.add("part ${i + 1} of $totalChunks: $chunkText")
        }
        return chunks
    }

    // ── /send ────────────────────────────────────────────────────────────

    data class SendParts(val number: String, val body: String)

    fun parseSendArgs(args: List<String>): SendParts? {
        if (args.size < 2) return null
        val number = args[0]
        val body = args.drop(1).joinToString(" ")
        return SendParts(number, body)
    }

    fun buildSendBody(text: String, files: List<EnvelopeFile>): String {
        val urls = files.flatMap { it.storageUrls }
        if (urls.isEmpty()) return text
        val sb = StringBuilder(text)
        for (url in urls) {
            sb.append("\n$url")
        }
        return sb.toString()
    }

    // ── /mms ─────────────────────────────────────────────────────────────

    data class MmsParts(val number: String, val url: String)

    fun parseMmsArgs(args: List<String>): MmsParts? {
        if (args.size < 2) return null
        return MmsParts(args[0], args.drop(1).joinToString(" "))
    }

    // ── /photo ────────────────────────────────────────────────────────────

    enum class CameraFacing { FRONT, BACK }

    /**
     * `args[0]` may be "front" / "back" (case-insensitive) — anything else
     * (or omitted) defaults to BACK.
     */
    fun parsePhotoFacing(args: List<String>): CameraFacing {
        val first = args.firstOrNull()?.lowercase()?.trim().orEmpty()
        return if (first == "front") CameraFacing.FRONT else CameraFacing.BACK
    }

    // ── /video ────────────────────────────────────────────────────────────

    const val VIDEO_DEFAULT_SECONDS: Int = 10
    const val VIDEO_MAX_SECONDS: Int = 30

    /**
     * `args[0]` is an integer count of seconds; clamped to [1, 30].
     * Garbage / missing falls back to the 10s default.
     */
    fun parseVideoSeconds(args: List<String>): Int {
        val raw = args.firstOrNull()?.toIntOrNull() ?: VIDEO_DEFAULT_SECONDS
        return raw.coerceIn(1, VIDEO_MAX_SECONDS)
    }

    // ── /audio ────────────────────────────────────────────────────────────

    const val AUDIO_DEFAULT_SECONDS: Int = 10
    const val AUDIO_MAX_SECONDS: Int = 60

    /**
     * `args[0]` is an integer count of seconds; clamped to [1, 60].
     * Audio max is longer than video because the files are tiny by
     * comparison (≈1 MB/min at 128 kbps, well under TempFile's 50 MiB
     * cap even for the full duration).
     */
    fun parseAudioSeconds(args: List<String>): Int {
        val raw = args.firstOrNull()?.toIntOrNull() ?: AUDIO_DEFAULT_SECONDS
        return raw.coerceIn(1, AUDIO_MAX_SECONDS)
    }

    // ── /location ─────────────────────────────────────────────────────────

    data class LocationSnapshot(
        val latitude: Double,
        val longitude: Double,
        val accuracyMeters: Float?,
        val ageMs: Long,
        val provider: String,
    )

    /**
     * Plain-text response per the spec:
     * `lat=… lng=… accuracy=…m age=… provider=…`
     */
    fun renderLocation(s: LocationSnapshot): String {
        val acc = s.accuracyMeters?.let { "%.1f".format(it) } ?: "?"
        return "lat=${"%.6f".format(s.latitude)} " +
            "lng=${"%.6f".format(s.longitude)} " +
            "accuracy=${acc}m " +
            "age=${formatAge(s.ageMs)} " +
            "provider=${s.provider}"
    }

    /** Short, human-readable age (seconds → minutes → hours). */
    fun formatAge(ms: Long): String {
        if (ms < 0) return "?"
        val secs = ms / 1000
        return when {
            secs < 60 -> "${secs}s"
            secs < 3600 -> "${secs / 60}m"
            else -> "${secs / 3600}h${(secs % 3600) / 60}m"
        }
    }

    // ── /say ──────────────────────────────────────────────────────────────

    /**
     * Re-join the trailing args as the text to speak. Empty input
     * returns null so the handler can reply with a usage hint.
     */
    fun parseSayText(args: List<String>): String? {
        val text = args.joinToString(" ").trim()
        return text.ifEmpty { null }
    }

    // ── /notify ───────────────────────────────────────────────────────────

    data class NotifyParts(val title: String, val body: String)

    /**
     * `<title>|<body>`. Args are re-joined, then split on the first `|`.
     * No pipe → entire input becomes the body, title defaults to
     * "a8s". Blank body returns null.
     */
    fun parseNotifyArgs(args: List<String>, defaultTitle: String = "a8s"): NotifyParts? {
        val joined = args.joinToString(" ").trim()
        if (joined.isEmpty()) return null
        val pipe = joined.indexOf('|')
        return if (pipe < 0) {
            NotifyParts(defaultTitle, joined)
        } else {
            val title = joined.substring(0, pipe).trim().ifEmpty { defaultTitle }
            val body = joined.substring(pipe + 1).trim()
            if (body.isEmpty()) null else NotifyParts(title, body)
        }
    }

    // ── /ls ───────────────────────────────────────────────────────────────

    const val LS_DEFAULT_PATH: String = "/sdcard/Download"

    data class LsEntry(
        val name: String,
        val size: Long,
        val lastModifiedMs: Long,
        val isDirectory: Boolean,
    )

    /**
     * Listing format: header line with the absolute path, then one
     * row per entry — `<type> <size>  <mtime>  <name>`. Sorted with
     * directories first, then by name. ISO-8601-ish mtime (UTC) so
     * the output is stable regardless of locale.
     */
    fun renderLs(absolutePath: String, entries: List<LsEntry>): String {
        val sorted = entries.sortedWith(
            compareByDescending<LsEntry> { it.isDirectory }.thenBy { it.name },
        )
        val header = "$absolutePath (${sorted.size} entries)"
        if (sorted.isEmpty()) return header
        val rows = sorted.joinToString("\n") { e ->
            val type = if (e.isDirectory) "d" else "f"
            val sizeStr = if (e.isDirectory) "-" else humanSize(e.size)
            "$type ${sizeStr.padStart(9)}  ${formatMtimeUtc(e.lastModifiedMs)}  ${e.name}"
        }
        return "$header\n$rows"
    }

    /** Used for both /ls rows and /cat size in the inline-vs-attachment branch. */
    fun humanSize(bytes: Long): String {
        if (bytes < 1024) return "${bytes}B"
        val units = listOf("KB", "MB", "GB", "TB")
        var v = bytes.toDouble() / 1024.0
        var i = 0
        while (v >= 1024.0 && i < units.lastIndex) { v /= 1024.0; i++ }
        return "%.1f%s".format(v, units[i])
    }

    private fun formatMtimeUtc(ms: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm'Z'", java.util.Locale.US)
        sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return sdf.format(java.util.Date(ms))
    }

    // ── /cat ──────────────────────────────────────────────────────────────

    /** Files <= this many bytes are returned inline; larger files attach. */
    const val CAT_INLINE_LIMIT_BYTES: Long = 10_000

    /**
     * Heuristic: if any of the first N bytes is a NUL or otherwise
     * non-printable + non-whitespace, treat as binary. Used to decide
     * whether the inline branch is even safe to take.
     */
    fun looksLikeText(sample: ByteArray): Boolean {
        if (sample.isEmpty()) return true
        for (b in sample) {
            val c = b.toInt() and 0xFF
            if (c == 0) return false
            if (c >= 0x20) continue
            // Allowed control bytes: tab, newline, carriage return.
            val isWhitespace = c == 0x09 || c == 0x0A || c == 0x0D
            if (!isWhitespace) return false
        }
        return true
    }

    // ── /download ────────────────────────────────────────────────────────

    data class DownloadParts(val url: String, val filename: String?)

    fun parseDownloadArgs(args: List<String>): DownloadParts? {
        if (args.isEmpty()) return null
        val url = args[0]
        val filename = if (args.size > 1) args.drop(1).joinToString(" ") else null
        return DownloadParts(url, filename)
    }

    // ── outbound SMS dedup (issue #36) ───────────────────────────────────

    /** Fingerprint for [CommandDedup] on commands that queue outbound SMS/RCS. */
    fun outboundSmsDedupKey(cmd: MqttRoute.Command): String? {
        return when (cmd.name) {
            "send" -> {
                val parts = parseSendArgs(cmd.args) ?: return null
                val body = buildSendBody(parts.body, cmd.files)
                "send|${normalizePhone(parts.number)}|$body"
            }
            "reply" -> {
                if (cmd.args.size < 2) return null
                val number = cmd.args[0]
                val text = cmd.args.drop(1).joinToString(" ")
                "reply|${normalizePhone(number)}|$text"
            }
            "mms" -> {
                val parts = parseMmsArgs(cmd.args) ?: return null
                "mms|${normalizePhone(parts.number)}|${parts.url}"
            }
            else -> null
        }
    }

    private fun normalizePhone(number: String): String =
        PhoneNormalize.normalizePhoneDigits(number)

    // ── /tell ─────────────────────────────────────────────────────────────

    data class TellParts(val rawAgent: String, val agent: String, val message: String)

    fun parseTellArgs(args: List<String>): TellParts? {
        if (args.size < 2) return null
        val rawAgent = args[0].trimEnd(',', '.', '!', '?', ':', ';')
        val agent = rawAgent.lowercase(Locale.ROOT)
        return TellParts(rawAgent, agent, args.drop(1).joinToString(" "))
    }

    // ── /<unknown> ───────────────────────────────────────────────────────

    val QUERY_COMMANDS: Set<String> = setOf(
        "info", "logs", "trace", "ls", "cat", "location", "nicknames"
    )

    /** Single source of truth for the `known commands` listing. */
    val KNOWN_COMMANDS: List<String> = listOf(
        "/info",
        "/logs [N]",
        "/trace [N]",
        "/send <number> <message>",
        "/mms <number> <url>",
        "/reply <number> <text>",
        "/tell <agent> <message>",
        "/nicknames add <nickname> for <agent>",
        "/update [--check|<url>]",
        "/screenshot",
        "/photo [front|back]",
        "/video [seconds]",
        "/audio [seconds]",
        "/location",
        "/say <text>",
        "/notify <title>|<body>",
        "/ls [<path>]",
        "/cat <path>",
        "/rm <path>",
        "/tap x y",
        "/longtap x y [ms]",
        "/swipe x1 y1 x2 y2 [ms]",
        "/key <NAME>",
        "/input <text>",
        "/find <label>",
        "/download <url> [filename]",
        "/macro <step>|<step>|…",
        "/config [get|set] <key> [value]",
        "/dashboard bg <url> | content <html> | clear",
        "/flushdedup",
    )
}
