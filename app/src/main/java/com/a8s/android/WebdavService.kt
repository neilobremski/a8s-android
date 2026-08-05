package com.a8s.android

import android.util.Base64
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom

/**
 * WebDAV backend. Pure stdlib HTTP — no third-party deps.
 *
 * Mirrors `apps/a8s/services/webdav.py` upstream. Configure with a
 * `webdav://` URL, which maps to HTTPS for the PUT:
 *
 * ```json
 * "fastmail": {
 *   "service": "webdav",
 *   "url": "webdav://webdav.example.com/dav/files/user/a8s",
 *   "base_url": "https://files.example.com/a8s",
 *   "user": "user@example.com",
 *   "password": "..."
 * }
 * ```
 *
 * `base_url` is optional and is what makes an upload useful here. The WebDAV
 * endpoint itself needs credentials, so a recipient cannot fetch from it; with
 * `base_url` the same object is reachable by a bare GET, which is the only way
 * this phone can hand a file to anyone. Without it, [store] still works and
 * still returns a URL, but the URL is credential-gated — see
 * [producesPublicUrl].
 *
 * Preferred over `tempfile_org`: it is storage the operator controls, it does
 * not expire on someone else's schedule, and a public paste host is the one an
 * ISP is likely to block.
 */
class WebdavService(
    override val id: String,
    davUrl: String,
    private val baseUrl: String? = null,
    private val user: String? = null,
    private val password: String? = null,
    private val prefix: String = DEFAULT_PREFIX,
    private val timeoutS: Int = DEFAULT_TIMEOUT_S,
) : StorageService {

    private val davBase: String = toHttps(davUrl).trimEnd('/')
    private val publicBase: String? = baseUrl?.trimEnd('/')

    override val producesPublicUrl: Boolean get() = publicBase != null

    override val preference: Int get() = PREFERENCE_OWN_STORE

    override fun store(file: File): String {
        if (file.length() > MAX_FILE_BYTES) {
            throw StorageException(
                "file ${file.name} exceeds ${MAX_FILE_BYTES / 1024 / 1024} MiB cap",
            )
        }
        val key = objectKey(file.name)
        val putUrl = "$davBase/$key"
        val conn = open(putUrl, "PUT")
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.setFixedLengthStreamingMode(file.length())
        try {
            file.inputStream().use { input ->
                conn.outputStream.use { out -> input.copyTo(out) }
            }
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw StorageException("webdav PUT responded $rc for ${file.name}")
            }
        } catch (e: IOException) {
            throw StorageException("webdav PUT failed for ${file.name}: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
        return publicBase?.let { "$it/$key" } ?: putUrl
    }

    /**
     * Fetch a URL under `base_url` by mapping it back onto the WebDAV path and
     * presenting credentials. A receiver without this service configured just
     * GETs the public URL instead — that is the point of `base_url`.
     */
    override fun retrieve(url: String, dest: File): Boolean {
        val key = relativeKey(url) ?: return false
        val resolved = AttachmentPath.bundleFile(dest.parentFile ?: File("."), dest.name)
        val target = resolved.file
            ?: throw StorageException("webdav: ${resolved.reason}")
        val conn = open("$davBase/$key", "GET")
        try {
            val rc = conn.responseCode
            if (rc == 404) return false
            if (rc !in 200..299) {
                throw StorageException("webdav GET responded $rc for $url")
            }
            target.parentFile?.mkdirs()
            val part = File(target.parentFile, target.name + ".part")
            conn.inputStream.use { input ->
                part.outputStream().use { out -> input.copyTo(out) }
            }
            if (!part.renameTo(target)) {
                part.delete()
                throw StorageException("webdav: cannot move the download into place")
            }
            return true
        } catch (e: IOException) {
            throw StorageException("webdav GET failed for $url: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
    }

    /** The object key when `url` sits under `base_url`, else null. */
    internal fun relativeKey(url: String): String? {
        val base = publicBase ?: return null
        val trimmed = url.trim()
        if (!trimmed.startsWith("$base/")) return null
        val rel = trimmed.removePrefix("$base/").substringBefore('?').trim('/')
        return rel.ifEmpty { null }
    }

    private fun objectKey(filename: String): String {
        val token = randomToken()
        val safe = File(filename).name
        return if (prefix.isEmpty()) "$token/$safe" else "$prefix/$token/$safe"
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutS * 1000
            readTimeout = timeoutS * 1000
            setRequestProperty("User-Agent", "a8s-android")
            authHeader()?.let { setRequestProperty("Authorization", it) }
        }

    private fun authHeader(): String? {
        val u = user ?: return null
        if (u.isBlank()) return null
        val raw = "$u:${password ?: ""}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    companion object {
        const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L
        const val DEFAULT_PREFIX: String = "a8s"
        const val DEFAULT_TIMEOUT_S: Int = 60

        private val random = SecureRandom()

        private fun randomToken(): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }

        /** `webdav://host/path` is the config spelling; the wire is HTTPS. */
        internal fun toHttps(url: String): String {
            val trimmed = url.trim()
            return when {
                trimmed.startsWith("webdav://", ignoreCase = true) ->
                    "https://" + trimmed.substring("webdav://".length)
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
                else -> throw IllegalArgumentException(
                    "webdav url must start with webdav:// or https:// (got '$url')",
                )
            }
        }
    }
}
