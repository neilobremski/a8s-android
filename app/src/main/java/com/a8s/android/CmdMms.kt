package com.a8s.android

import java.io.File

object CmdMms {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val parts = CmdHelpers.parseMmsArgs(cmd.args)
        if (parts == null) {
            service.replyToSender(config, cmd, "usage: /mms <number> <url>")
            return
        }

        val destDir = File(service.cacheDir, "mms-outbound")
        destDir.mkdirs()
        val dest = File(destDir, "mms-${System.currentTimeMillis()}.jpg")

        var downloaded = false
        for (svc in config.services) {
            try {
                if (svc.retrieve(parts.url, dest)) {
                    downloaded = true
                    break
                }
            } catch (e: StorageException) {
                A8sAndroid.log("MMS download from ${svc.id} failed: ${e.message}")
            }
        }

        if (!downloaded) {
            downloaded = tryRawDownload(parts.url, dest)
        }

        if (!downloaded) {
            service.sendSms(parts.number, "Media: ${parts.url}")
            service.replyToSender(
                config, cmd,
                "Could not download media; sent URL as text SMS to ${parts.number}",
            )
            return
        }

        // MMS PDU sending requires default SMS app role. For now, send
        // the URL as text SMS and note that the file was downloaded
        // successfully (future: proper MMS when app is default SMS).
        service.sendSms(parts.number, "Media: ${parts.url}")
        service.replyToSender(
            config, cmd,
            "Sent URL as text SMS to ${parts.number} (MMS requires default SMS app role). " +
                "File downloaded: ${dest.length()} bytes",
        )
        dest.delete()
    }

    private fun tryRawDownload(url: String, dest: File): Boolean {
        // One downloader with one set of rules: https only, a bounded redirect
        // chain, and a size cap. See [HttpGet].
        return when (val r = HttpGet.download(url, dest)) {
            is HttpGet.Result.Ok -> dest.length() > 0
            is HttpGet.Result.NotHttps -> {
                A8sAndroid.log("MMS download refused: $url is not https")
                false
            }
            is HttpGet.Result.Failed -> {
                A8sAndroid.log("MMS download failed: ${r.reason}")
                false
            }
        }
    }
}
