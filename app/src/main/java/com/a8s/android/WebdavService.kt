package com.a8s.android

import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * WebDAV backend. Transport is OkHttp — `HttpURLConnection` refuses methods
 * outside its fixed list, and WebDAV needs MKCOL.
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
    options: Options = Options(),
) : StorageService {

    /** Basic-auth pair for the DAV endpoint. Held together so the two never
     *  drift apart, and so the constructor stays readable. */
    data class Credentials(val user: String, val password: String)

    /** Per-service tuning; everything has a default. `http` is a test seam —
     *  production always builds the default client. */
    data class Options(
        val baseUrl: String? = null,
        val credentials: Credentials? = null,
        val prefix: String = DEFAULT_PREFIX,
        val timeoutS: Int = DEFAULT_TIMEOUT_S,
        internal val http: OkHttpClient? = null,
    )

    private val davBase: String = toHttps(davUrl).trimEnd('/')
    private val publicBase: String? = options.baseUrl?.trimEnd('/')
    private val credentials: Credentials? = options.credentials
    private val prefix: String = options.prefix

    private val client: OkHttpClient = options.http ?: OkHttpClient.Builder()
        .connectTimeout(options.timeoutS.toLong(), TimeUnit.SECONDS)
        .readTimeout(options.timeoutS.toLong(), TimeUnit.SECONDS)
        .writeTimeout(options.timeoutS.toLong(), TimeUnit.SECONDS)
        // A DAV endpoint answers in place; a 301/302/303 would let OkHttp
        // rewrite a PUT to a GET and read a 200 listing as a stored file.
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override val producesPublicUrl: Boolean get() = publicBase != null

    override val preference: Int get() = PREFERENCE_OWN_STORE

    override fun store(file: File): String {
        if (file.length() > MAX_FILE_BYTES) {
            throw StorageException(
                "file ${file.name} exceeds ${MAX_FILE_BYTES / 1024 / 1024} MiB cap",
            )
        }
        val key = objectKey(file.name)
        makeCollections(key)
        val putUrl = "$davBase/$key"
        try {
            client.newCall(
                request(putUrl).put(file.asRequestBody(OCTET_STREAM)).build(),
            ).execute().use { resp ->
                if (resp.code !in 200..299) {
                    throw StorageException("webdav PUT responded ${resp.code} for ${file.name}")
                }
            }
        } catch (e: IOException) {
            throw StorageException("webdav PUT failed for ${file.name}: ${e.message}", e)
        }
        return publicBase?.let { "$it/$key" } ?: putUrl
    }

    /** Collections already created (or found existing) this process, so a
     *  busy session pays for each ancestor once. */
    private val madeCollections = HashSet<String>()

    /**
     * MKCOL every directory above `key`, outermost first — mirrors
     * `apps/a8s/services/webdav.py` upstream. WebDAV PUT does not create
     * parent collections: a PUT into a directory that does not exist answers
     * 409 Conflict, and every object key carries a fresh random directory,
     * so that 409 is the normal case, not the rare one. 405 means the
     * collection is already there.
     */
    private fun makeCollections(key: String) {
        for (path in ancestorPaths(key)) {
            if (path in madeCollections) continue
            val rc = try {
                mkcol("$davBase/$path")
            } catch (e: IOException) {
                throw StorageException("webdav MKCOL failed for $path: ${e.message}", e)
            }
            if (rc !in 200..299 && rc != HTTP_METHOD_NOT_ALLOWED) {
                throw StorageException("webdav MKCOL responded $rc for $path")
            }
            madeCollections.add(path)
        }
    }

    private fun mkcol(url: String): Int =
        client.newCall(request(url).method("MKCOL", null).build()).execute().use { it.code }

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
        try {
            client.newCall(request("$davBase/$key").get().build()).execute().use { resp ->
                if (resp.code == 404) return false
                if (resp.code !in 200..299) {
                    throw StorageException("webdav GET responded ${resp.code} for $url")
                }
                target.parentFile?.mkdirs()
                val part = File(target.parentFile, target.name + ".part")
                val body = resp.body
                    ?: throw StorageException("webdav GET returned no body for $url")
                body.byteStream().use { input ->
                    part.outputStream().use { out -> input.copyTo(out) }
                }
                if (!part.renameTo(target)) {
                    part.delete()
                    throw StorageException("webdav: cannot move the download into place")
                }
                return true
            }
        } catch (e: IOException) {
            throw StorageException("webdav GET failed for $url: ${e.message}", e)
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

    private fun request(url: String): Request.Builder =
        Request.Builder().url(url).header("User-Agent", "a8s-android").apply {
            authHeader()?.let { header("Authorization", it) }
        }

    private fun authHeader(): String? {
        val c = credentials ?: return null
        if (c.user.isBlank()) return null
        val raw = "${c.user}:${c.password}".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.encodeToString(raw, Base64.NO_WRAP)
    }

    companion object {
        const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L
        const val DEFAULT_PREFIX: String = "a8s"
        const val DEFAULT_TIMEOUT_S: Int = 60
        private const val HTTP_METHOD_NOT_ALLOWED = 405
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        /** Every collection above `key`, outermost first: the paths MKCOL
         *  must create, in the order it must create them. */
        internal fun ancestorPaths(key: String): List<String> {
            val parts = key.trim('/').split('/').dropLast(1).filter { it.isNotEmpty() }
            val out = mutableListOf<String>()
            var path = ""
            for (part in parts) {
                path = if (path.isEmpty()) part else "$path/$part"
                out.add(path)
            }
            return out
        }

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
