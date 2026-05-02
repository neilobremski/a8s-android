package com.a8s.android

import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * TempFile.org backend. Pure stdlib HTTP — no third-party deps.
 *
 * Endpoints mirror the Python `services/tempfile_org.py` contract:
 *   - POST `<base>/api/upload/local` (multipart/form-data, `file` part,
 *     `expirable` / `expiry_seconds`, depending on what the API
 *     accepts; we send `Hours` because the public form uses it).
 *     Response is JSON containing the public URL.
 *   - GET  `<retrieved-url>download`  (i.e. append `download` to the
 *     short URL returned at upload time) — body is the raw file bytes.
 *
 * Per-file size cap matches the existing local-only `MAX_FILE_BYTES`
 * (50 MiB) so we don't accidentally upload something the upstream
 * service refuses (its hard cap is 100 MB).
 */
class TempFileOrgService(
    override val id: String,
    private val base: String,
    private val expiryHours: Int = DEFAULT_EXPIRY_HOURS,
    private val timeoutS: Int = DEFAULT_TIMEOUT_S,
) : StorageService {

    init {
        require(expiryHours in ALLOWED_EXPIRY) {
            "tempfile_org expiry_hours must be one of $ALLOWED_EXPIRY (got $expiryHours)"
        }
    }

    private val baseHost: String = URL(base).host

    override fun store(file: File): String {
        if (file.length() > MAX_FILE_BYTES) {
            throw StorageException(
                "file ${file.name} exceeds ${MAX_FILE_BYTES / 1024 / 1024} MiB cap",
            )
        }
        val boundary = "----a8s-${System.currentTimeMillis()}"
        val url = URL("$base/api/upload/local")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            doOutput = true
            requestMethod = "POST"
            connectTimeout = timeoutS * 1000
            readTimeout = timeoutS * 1000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("User-Agent", "a8s-android")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                writePart(out, boundary, "Hours", expiryHours.toString())
                writeFilePart(out, boundary, "file", file)
                out.writeBytes("--$boundary--\r\n")
            }
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw StorageException("tempfile_org upload responded $rc for ${file.name}")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseUploadUrl(body)
                ?: throw StorageException("tempfile_org upload: no URL in response: $body")
        } catch (e: IOException) {
            throw StorageException("tempfile_org upload failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    override fun retrieve(url: String, dest: File): Boolean {
        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            return false
        }
        // Only handle URLs we recognize as ours (host match).
        if (!matchesHost(parsed.host)) return false
        val downloadUrl = if (url.endsWith("/")) "${url}download" else "$url/download"
        val conn = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutS * 1000
            readTimeout = timeoutS * 1000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "a8s-android")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw StorageException("tempfile_org download $downloadUrl responded $rc")
            }
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            return true
        } catch (e: IOException) {
            throw StorageException("tempfile_org download failed: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    private fun matchesHost(host: String?): Boolean =
        host != null && host.equals(baseHost, ignoreCase = true)

    private fun writePart(out: DataOutputStream, boundary: String, name: String, value: String) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
        out.write(value.toByteArray(StandardCharsets.UTF_8))
        out.writeBytes("\r\n")
    }

    private fun writeFilePart(out: DataOutputStream, boundary: String, name: String, file: File) {
        out.writeBytes("--$boundary\r\n")
        out.writeBytes(
            "Content-Disposition: form-data; name=\"$name\"; filename=\"${file.name}\"\r\n",
        )
        out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
        file.inputStream().use { it.copyTo(out) }
        out.writeBytes("\r\n")
    }

    companion object {
        const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L  // 50 MiB
        const val DEFAULT_EXPIRY_HOURS: Int = 24
        const val DEFAULT_TIMEOUT_S: Int = 30
        val ALLOWED_EXPIRY: Set<Int> = setOf(1, 6, 24, 48)

        /** Extract the public URL from a tempfile.org upload response.
         *  Pulled out for testability — the actual payload shape is
         *  observed at runtime; we tolerate variations by scanning for
         *  the first `https?://...` token that looks plausible.
         *  Pure: no I/O, no Android. */
        fun parseUploadUrl(body: String): String? {
            // Try JSON `url` field first.
            val jsonMatch = Regex("""["']url["']\s*:\s*["']([^"']+)["']""")
                .find(body)
            if (jsonMatch != null) return jsonMatch.groupValues[1]
            // Fallback: any URL in the response body.
            val urlMatch = Regex("""https?://[^\s"'<>]+""").find(body)
            return urlMatch?.value
        }
    }
}
