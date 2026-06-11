package com.a8s.android

import android.os.Environment
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object CmdDownload {
    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val parts = CmdHelpers.parseDownloadArgs(cmd.args)
        if (parts == null) {
            service.replyToSender(config, cmd, "usage: /download <url> [filename]")
            return
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val filename = parts.filename ?: parts.url.substringAfterLast("/").substringBefore("?")
            .ifEmpty { "download-${System.currentTimeMillis()}" }
        val dest = File(downloadsDir, filename)

        var downloaded = false
        for (svc in config.services) {
            try {
                if (svc.retrieve(parts.url, dest)) {
                    downloaded = true
                    break
                }
            } catch (e: StorageException) {
                A8sAndroid.log("Download from ${svc.id} failed: ${e.message}")
            }
        }

        if (!downloaded) {
            downloaded = rawDownload(parts.url, dest)
        }

        if (downloaded) {
            service.replyToSender(config, cmd,
                "Downloaded to ${dest.absolutePath} (${CmdHelpers.humanSize(dest.length())})")
        } else {
            dest.delete()
            service.replyToSender(config, cmd,
                "Download failed: could not retrieve ${parts.url}")
        }
    }

    internal fun rawDownload(urlStr: String, dest: File): Boolean {
        return try {
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "a8s-android")
            if (conn.responseCode !in 200..299) {
                conn.disconnect()
                return false
            }
            dest.parentFile?.mkdirs()
            conn.inputStream.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
            conn.disconnect()
            dest.length() > 0
        } catch (e: Exception) {
            A8sAndroid.log("Download raw HTTP failed: ${e.message}")
            false
        }
    }
}
