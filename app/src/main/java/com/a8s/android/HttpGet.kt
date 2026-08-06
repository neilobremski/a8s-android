package com.a8s.android

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Plain HTTPS download for storage URLs that carry their own authorization.
 *
 * Mirrors `apps/a8s/services/http_get.py` upstream. A presigned S3 link, an
 * `rclone link` result, and any other public object URL are fetched with an
 * ordinary GET, so the receiver needs no storage service, no credentials, and
 * no knowledge of which backend produced the URL. This is what lets a sender
 * add a backend without every receiver being reconfigured.
 *
 * The sender of an envelope chooses this URL, which is why the rules are
 * strict:
 *
 * - https only. These URLs carry their authorization in the query string.
 * - At most [MAX_REDIRECTS] hops, and every hop obeys the same scheme rule.
 *   Object stores redirect a share URL to the host holding the bytes, so
 *   refusing outright breaks ordinary links; following without a limit lets
 *   the sender pick the last host as well as the first.
 * - A size cap, checked against `Content-Length` and again while streaming,
 *   because the header can lie or be absent.
 */
object HttpGet {

    const val MAX_REDIRECTS: Int = 3
    const val MAX_FILE_BYTES: Long = 50L * 1024L * 1024L

    /** Outcome of one attempt. [NotHttps] keeps "wrong scheme" separate from
     *  "https but the transfer failed", the way the Python contract does. */
    sealed class Result {
        object Ok : Result()
        object NotHttps : Result()
        data class Failed(val reason: String) : Result()
    }

    fun isHttps(url: String): Boolean =
        runCatching { URI(url.trim()).scheme?.lowercase() }.getOrNull() == "https"

    /**
     * Download [url] into [dest]. Writes through a `.part` sibling and renames,
     * so a partial transfer never looks like a complete attachment.
     */
    fun download(
        url: String,
        dest: File,
        maxBytes: Long = MAX_FILE_BYTES,
        timeoutS: Int = DEFAULT_TIMEOUT_S,
    ): Result {
        if (!isHttps(url)) return Result.NotHttps

        var current = url.trim()
        var hops = 0
        while (true) {
            val conn = try {
                open(current, timeoutS)
            } catch (e: IOException) {
                return Result.Failed("cannot open $current: ${e.message}")
            }
            try {
                val code = try {
                    conn.responseCode
                } catch (e: IOException) {
                    return Result.Failed("no response from $current: ${e.message}")
                }
                if (code in REDIRECT_CODES) {
                    val location = conn.getHeaderField("Location")
                        ?: return Result.Failed("redirect $code with no Location")
                    val next = runCatching { URL(URL(current), location).toString() }
                        .getOrNull()
                        ?: return Result.Failed("unusable redirect target: $location")
                    if (!isHttps(next)) {
                        return Result.Failed("refusing a redirect to a non-https target")
                    }
                    if (++hops > MAX_REDIRECTS) {
                        return Result.Failed("more than $MAX_REDIRECTS redirects")
                    }
                    current = next
                    continue
                }
                if (code !in 200..299) {
                    return Result.Failed("HTTP $code for $current")
                }
                val declared = conn.getHeaderField("Content-Length")?.toLongOrNull()
                if (declared != null && declared > maxBytes) {
                    return Result.Failed("Content-Length $declared exceeds cap $maxBytes")
                }
                return stream(conn, dest, maxBytes)
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun open(url: String, timeoutS: Int): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutS * 1000
            readTimeout = timeoutS * 1000
            // Redirects are handled here so each hop can be checked.
            instanceFollowRedirects = false
            setRequestProperty("User-Agent", "a8s-android")
        }

    private fun stream(conn: HttpURLConnection, dest: File, maxBytes: Long): Result {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var written = 0L
        try {
            conn.inputStream.use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(CHUNK)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        written += n
                        if (written > maxBytes) {
                            part.delete()
                            return Result.Failed("body exceeds cap $maxBytes")
                        }
                        out.write(buf, 0, n)
                    }
                }
            }
        } catch (e: IOException) {
            part.delete()
            return Result.Failed("transfer failed: ${e.message}")
        }
        if (!part.renameTo(dest)) {
            part.delete()
            return Result.Failed("cannot move the download into place")
        }
        return Result.Ok
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
    private const val CHUNK = 64 * 1024
    private const val DEFAULT_TIMEOUT_S = 60
}
