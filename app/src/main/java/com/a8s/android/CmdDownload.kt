package com.a8s.android

import android.os.Environment
import java.io.File

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

    /**
     * One downloader, one set of rules — https only, a bounded redirect chain,
     * and a size cap. See [HttpGet]. `/download` and `/dashboard` share it with
     * the attachment path so a URL is fetched the same way everywhere.
     */
    internal fun rawDownload(urlStr: String, dest: File): Boolean {
        return when (val r = HttpGet.download(urlStr, dest)) {
            is HttpGet.Result.Ok -> dest.length() > 0
            is HttpGet.Result.NotHttps -> {
                A8sAndroid.log("Download refused: $urlStr is not https")
                false
            }
            is HttpGet.Result.Failed -> {
                A8sAndroid.log("Download failed: ${r.reason}")
                false
            }
        }
    }
}
