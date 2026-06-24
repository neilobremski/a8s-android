package com.a8s.android

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Structured, bounded ring of inbound/outbound message transactions.
 * Easier to diagnose than the free-form log ring — especially file
 * attachment paths (phone-agent → SMS, MQTT command with `files[]`).
 *
 * Surfaced via `/trace [N]` (newest first). Plain `/logs` is unchanged.
 */
object TransactionTrace {

    enum class Status { OK, PARTIAL, FAIL, SKIP, DROP }

    data class Event(
        val timeMs: Long = System.currentTimeMillis(),
        /** Envelope ULID when available. */
        val txnId: String = "",
        val flow: String,
        val status: Status,
        val from: String = "",
        val to: String = "",
        /** One-line summary for scanning. */
        val summary: String = "",
        /** Multi-line detail (file outcomes, drop reasons, etc.). */
        val detail: String = "",
    )

    private const val MAX_ENTRIES = 100

    private val ring = ArrayDeque<Event>()

    @Synchronized
    fun record(event: Event) {
        ring.addLast(event)
        while (ring.size > MAX_ENTRIES) ring.removeFirst()
    }

    @Synchronized
    fun recent(limit: Int = 20): List<Event> {
        val n = limit.coerceIn(1, MAX_ENTRIES)
        return ring.takeLast(n).asReversed()
    }

    @Synchronized
    fun clear() {
        ring.clear()
    }

    /** Human-readable summary of envelope `files[]` for trace lines. */
    fun summarizeEnvelopeFiles(json: JSONObject): String {
        val raw = json.optJSONArray("files") ?: return "files: none"
        val parsed = parseEnvelopeFiles(json)
        val declared = raw.length()
        val parsedNote = if (declared != parsed.size && declared > 0) {
            " ($declared in JSON, ${parsed.size} parsed — check filename/storage shape)"
        } else {
            ""
        }
        if (parsed.isEmpty()) return "files: ${declared} declared, 0 usable$parsedNote"
        val parts = parsed.map { ef ->
            val urls = ef.storageUrls.size
            "${ef.filename}(${urls} url${if (urls == 1) "" else "s"})"
        }
        return "files: ${parts.joinToString(", ")}$parsedNote"
    }

    fun summarizeDownloadResults(results: List<FileDownloader.DownloadResult>): String {
        if (results.isEmpty()) return "files: none"
        return results.joinToString("\n") { r ->
            val tag = when (r.outcome) {
                FileDownloader.DownloadOutcome.OK ->
                    "  • ${r.filename}: OK ${r.file?.length() ?: 0}B ${r.detail}".trim()
                FileDownloader.DownloadOutcome.NO_URLS ->
                    "  • ${r.filename}: NO storage urls in envelope"
                FileDownloader.DownloadOutcome.FAILED ->
                    "  • ${r.filename}: download failed — ${r.detail}" +
                        (r.fallbackUrl?.let { " (SMS will include $it)" } ?: "")
            }
            tag
        }
    }

    /** Mask phone numbers in `to` when they look like digits. */
    fun maskTo(to: String): String =
        if (to.contains(Regex("[0-9]{7,}"))) PhoneNormalize.maskNumber(to) else to

    private fun formatTime(ms: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(ms))
    }

    private fun shortId(id: String): String =
        if (id.length <= 8) id.ifEmpty { "?" } else id.take(8)

    /** Render the last [limit] events, newest first. */
    fun render(limit: Int = 20): String {
        val events = recent(limit)
        if (events.isEmpty()) return "trace: empty (no transactions recorded yet)"
        val header = "trace: last ${events.size} transaction(s), newest first"
        val lines = events.map { e ->
            val head = "${formatTime(e.timeMs)}  ${e.status.name.padEnd(7)}  ${e.flow.padEnd(8)}  " +
                "${shortId(e.txnId).padEnd(8)}  ${e.from} → ${maskTo(e.to)}"
            val body = buildList {
                if (e.summary.isNotBlank()) add("  ${e.summary}")
                if (e.detail.isNotBlank()) add(e.detail.prependIndent("  "))
            }.joinToString("\n")
            if (body.isEmpty()) head else "$head\n$body"
        }
        return (listOf(header, "") + lines).joinToString("\n")
    }
}
