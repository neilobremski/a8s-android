package com.a8s.android

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Amazon S3 backend — and any S3-compatible endpoint. Pure stdlib HTTP with
 * hand-rolled SigV4 ([SigV4]); no AWS SDK on the phone.
 *
 * Mirrors `apps/a8s/services/s3.py` upstream. Configure with an `s3://` URL,
 * which names the bucket and optionally a key prefix:
 *
 * ```json
 * "bucket": {
 *   "service": "s3",
 *   "url": "s3://my-bucket/a8s",
 *   "region": "us-west-2",
 *   "access_key": "AKIA...",
 *   "secret_key": "..."
 * }
 * ```
 *
 * [store] PUTs to `s3://<bucket>/<prefix>/<token>/<filename>` and returns a
 * **presigned GET URL**, not an `s3://` URI: the capability to fetch travels
 * inside the envelope, so a receiver needs no AWS credentials of its own —
 * exactly like the tempfile-class services.
 *
 * Keys live under `prefix` so the bucket owner can point a lifecycle rule at
 * it and let expiry be the bucket's job. Nothing here deletes: a service that
 * reaches back into a bucket to remove objects is a foot nuke, and S3 already
 * has the feature.
 *
 * `endpoint_url` points the service at an S3-compatible host, addressed
 * path-style (`<endpoint>/<bucket>/<key>`) since virtual-host naming is an
 * AWS property.
 */
class S3Service(
    override val id: String,
    s3Url: String,
    private val creds: SigV4.Credentials,
    options: Options = Options(),
) : StorageService {

    /** Per-service tuning; everything has a default. */
    data class Options(
        val region: String = DEFAULT_REGION,
        val prefix: String? = null,
        val presignHours: Int = DEFAULT_PRESIGN_HOURS,
        val timeoutS: Int = DEFAULT_TIMEOUT_S,
        val endpointUrl: String? = null,
        val nowMillis: () -> Long = { System.currentTimeMillis() },
    )

    private val bucket: String
    private val prefix: String
    private val region: String = options.region
    private val presignHours: Int = options.presignHours
    private val timeoutS: Int = options.timeoutS
    private val endpointHost: String? // host[:port] of a custom endpoint
    private val nowMillis: () -> Long = options.nowMillis

    init {
        val (b, p) = splitBucketUrl(s3Url)
        require(b.isNotBlank()) { "storage $id: url must name a bucket, e.g. s3://my-bucket" }
        require(creds.accessKey.isNotBlank() && creds.secretKey.isNotBlank()) {
            "storage $id: s3 needs access_key and secret_key"
        }
        require(presignHours in 1..MAX_PRESIGN_HOURS) {
            "storage $id: presign_hours must be 1..$MAX_PRESIGN_HOURS"
        }
        bucket = b
        this.prefix = (options.prefix ?: p.ifBlank { DEFAULT_PREFIX }).trim('/')
        endpointHost = options.endpointUrl?.let {
            val uri = URI(it.trim())
            require(uri.scheme?.lowercase() == "https") {
                "storage $id: endpoint_url must be https"
            }
            if (uri.port > 0) "${uri.host}:${uri.port}" else uri.host
        }
    }

    /** A presigned GET is a bare URL — the receiver needs no credentials. */
    override val producesPublicUrl: Boolean get() = true

    override val preference: Int get() = PREFERENCE_OWN_STORE

    override fun store(file: File): String {
        if (file.length() > MAX_FILE_BYTES) {
            throw StorageException(
                "file ${file.name} exceeds ${MAX_FILE_BYTES / 1024 / 1024} MiB cap",
            )
        }
        val key = objectKey(file.name)
        val target = SigV4.Target(host(), requestPath(key))
        val headers = SigV4.signedHeaders(
            "PUT", target, creds, signContext(), sha256File(file),
        )
        val conn = open("https://${target.host}${target.path}", "PUT")
        try {
            for ((k, v) in headers) {
                // host is implied by the URL and restricted on the connection.
                if (k != "host" && k != "authorization") conn.setRequestProperty(k, v)
            }
            conn.setRequestProperty("Authorization", headers.getValue("authorization"))
            conn.doOutput = true
            conn.setFixedLengthStreamingMode(file.length())
            file.inputStream().use { input ->
                conn.outputStream.use { out -> input.copyTo(out) }
            }
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw StorageException("s3 PUT responded $rc for ${file.name}")
            }
        } catch (e: IOException) {
            throw StorageException("s3 PUT failed for ${file.name}: ${e.message}", e)
        } finally {
            conn.disconnect()
        }
        return presignedGet(key)
    }

    /**
     * `s3://` URIs get a presigned URL first — the phone holds credentials, so
     * it mints one and downloads like any other receiver. URLs already over
     * https are presigned already and download directly. Either way the bytes
     * come through [HttpGet], which keeps the https-only and size-cap rules.
     */
    override fun retrieve(url: String, dest: File): Boolean {
        val key = keyFromUrl(url) ?: return false
        val resolved = AttachmentPath.bundleFile(dest.parentFile ?: File("."), dest.name)
        val target = resolved.file ?: throw StorageException("s3: ${resolved.reason}")
        val getUrl = when {
            url.trim().startsWith("s3://", ignoreCase = true) -> presignedGet(key)
            else -> url.trim()
        }
        return when (val r = HttpGet.download(getUrl, target, timeoutS = timeoutS)) {
            HttpGet.Result.Ok -> true
            HttpGet.Result.NotHttps -> false
            is HttpGet.Result.Failed -> throw StorageException("s3 GET failed for $url: ${r.reason}")
        }
    }

    /** The object key when `url` addresses this bucket, else null — the
     *  `s3://bucket/key` form plus the virtual-host and path-style https
     *  forms presigning produces. */
    internal fun keyFromUrl(url: String): String? {
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return null
        }
        val path = (uri.path ?: "").trimStart('/')
        if (uri.scheme?.lowercase() == "s3") {
            return path.takeIf { uri.host == bucket && it.isNotEmpty() }
        }
        if (uri.scheme?.lowercase() != "https") return null
        val host = (uri.host ?: "").lowercase()
        if (host.startsWith("$bucket.")) {
            return path.ifEmpty { null }
        }
        val first = path.substringBefore('/')
        val rest = path.substringAfter('/', "")
        return if (first == bucket && rest.isNotEmpty()) rest else null
    }

    internal fun presignedGet(key: String, expiresSeconds: Long = presignHours * 3600L): String =
        SigV4.presignGet(SigV4.Target(host(), requestPath(key)), creds, signContext(), expiresSeconds)

    /** Virtual-host on AWS (`bucket.s3.<region>.amazonaws.com`); the custom
     *  endpoint host as configured. */
    internal fun host(): String = endpointHost ?: "$bucket.s3.$region.amazonaws.com"

    /** `/<key>` on AWS's virtual-host addressing; `/<bucket>/<key>` against a
     *  custom endpoint, where the bucket travels in the path. */
    internal fun requestPath(key: String): String =
        if (endpointHost == null) "/$key" else "/$bucket/$key"

    private fun objectKey(filename: String): String {
        val token = randomToken()
        val safe = File(filename).name
        return if (prefix.isEmpty()) "$token/$safe" else "$prefix/$token/$safe"
    }

    private fun signContext(): SigV4.Context {
        val utc = Instant.ofEpochMilli(nowMillis()).atOffset(ZoneOffset.UTC)
        return SigV4.Context(region, utc.format(AMZ_DATE), utc.format(DATE_STAMP))
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = timeoutS * 1000
            readTimeout = timeoutS * 1000
            setRequestProperty("User-Agent", "a8s-android")
        }

    private fun sha256File(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(CHUNK)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L
        const val DEFAULT_REGION: String = "us-east-1"
        const val DEFAULT_PREFIX: String = "a8s"
        const val DEFAULT_PRESIGN_HOURS: Int = 24
        const val MAX_PRESIGN_HOURS: Int = 168 // S3 SigV4 ceiling is 7 days
        const val DEFAULT_TIMEOUT_S: Int = 60
        private const val CHUNK: Int = 64 * 1024

        private val AMZ_DATE: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
        private val DATE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** `s3://bucket/some/prefix` -> ("bucket", "some/prefix"). */
        internal fun splitBucketUrl(url: String): Pair<String, String> {
            val uri = try {
                URI(url.trim())
            } catch (e: Exception) {
                return "" to ""
            }
            if (uri.scheme?.lowercase() != "s3") return "" to ""
            return (uri.host ?: "") to (uri.path ?: "").trim('/')
        }

        private val random = SecureRandom()

        private fun randomToken(): String {
            val bytes = ByteArray(8)
            random.nextBytes(bytes)
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
