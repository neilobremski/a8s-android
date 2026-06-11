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
        return try {
            val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
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
            A8sAndroid.log("MMS raw download failed: ${e.message}")
            false
        }
    }
}
