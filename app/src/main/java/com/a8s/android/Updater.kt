package com.a8s.android

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Self-update plumbing. Pure functions where possible (parsing, version
 * comparison) so they're unit-testable; HTTP + filesystem live in the
 * thin wrappers at the bottom.
 *
 * Design choice: stock Android won't let us install an APK silently.
 * `/update` downloads the latest release and triggers the system's
 * install confirmation dialog via `ACTION_VIEW` + FileProvider. The
 * operator taps "Install" once; the new APK signs with the same
 * stable debug keystore (`app/debug.keystore`) so it upgrades in
 * place without conflict.
 */
object Updater {

    val githubRepo: String get() = BuildConfig.GITHUB_REPO

    data class ReleaseInfo(
        val tagName: String,         // e.g. "v1.8.0"
        val versionName: String,     // e.g. "1.8.0"
        val apkUrl: String,
        val apkName: String,
        val sizeBytes: Long,
        val publishedAt: String,
    )

    /** Parse the GitHub API response shape. Returns null on a malformed
     *  payload or if no matching APK asset is found. */
    fun parseReleaseJson(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tag = obj.optString("tag_name")
            if (tag.isBlank()) return null
            val version = tag.removePrefix("v")
            val publishedAt = obj.optString("published_at").ifBlank { "?" }
            val assets = obj.optJSONArray("assets") ?: return null
            var picked: ReleaseInfo? = null
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                val name = a.optString("name")
                // Match release.yml's filename convention.
                if (name.startsWith("a8s-android-") && name.endsWith("-debug.apk")) {
                    picked = ReleaseInfo(
                        tagName = tag,
                        versionName = version,
                        apkUrl = a.optString("browser_download_url"),
                        apkName = name,
                        sizeBytes = a.optLong("size", -1),
                        publishedAt = publishedAt,
                    )
                    break
                }
            }
            picked
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Compare two semantic-style versions ("1.8.0" vs "1.10.2").
     * Returns -1 if a < b, 0 if equal, 1 if a > b. Non-numeric segments
     * compare lexically. Missing trailing components are treated as 0.
     */
    fun compareVersions(a: String, b: String): Int {
        val ap = a.split(".")
        val bp = b.split(".")
        val n = maxOf(ap.size, bp.size)
        for (i in 0 until n) {
            val l = ap.getOrNull(i) ?: "0"
            val r = bp.getOrNull(i) ?: "0"
            val li = l.toIntOrNull()
            val ri = r.toIntOrNull()
            val cmp = if (li != null && ri != null) li.compareTo(ri) else l.compareTo(r)
            if (cmp != 0) return cmp.coerceIn(-1, 1)
        }
        return 0
    }

    fun renderCheck(installed: String, latest: ReleaseInfo): String {
        val cmp = compareVersions(installed, latest.versionName)
        val status = when {
            cmp < 0 -> "UPDATE AVAILABLE"
            cmp == 0 -> "up to date"
            else -> "installed is newer (?)"
        }
        return buildString {
            appendLine("Installed: v$installed")
            appendLine("Latest:    ${latest.tagName} (${humanSize(latest.sizeBytes)}, published ${latest.publishedAt})")
            append("Status:    $status")
        }
    }

    fun humanSize(bytes: Long): String {
        if (bytes < 0) return "?"
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        return "%.2f MB".format(kb / 1024.0)
    }

    // ---------- HTTP / IO (thin wrappers; not unit-tested in this PR) ----------

    fun fetchLatestRelease(repo: String = githubRepo): ReleaseInfo {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "a8s-android")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw IOException("GitHub API responded $rc")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return parseReleaseJson(body)
                ?: throw IOException("no a8s-android-*-debug.apk asset found in latest release")
        } finally {
            conn.disconnect()
        }
    }

    /** Download `url` to `dest`, replacing any existing file. Returns
     *  the destination File. Throws on HTTP error. */
    fun downloadTo(url: String, dest: File): File {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "a8s-android")
        }
        try {
            val rc = conn.responseCode
            if (rc !in 200..299) {
                throw IOException("download GET $url responded $rc")
            }
            conn.inputStream.use { input ->
                dest.outputStream().use { out ->
                    input.copyTo(out)
                }
            }
            return dest
        } finally {
            conn.disconnect()
        }
    }
}
