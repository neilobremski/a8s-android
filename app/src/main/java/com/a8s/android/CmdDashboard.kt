package com.a8s.android

import java.io.File

object CmdDashboard {
    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        if (cmd.args.isEmpty()) {
            service.replyToSender(config, cmd,
                "usage: /dashboard bg <url> | /dashboard content <html> | /dashboard clear")
            return
        }

        when (cmd.args[0].lowercase()) {
            "bg" -> handleBg(service, config, cmd)
            "content" -> handleContent(service, config, cmd)
            "clear" -> handleClear(service, config, cmd)
            else -> service.replyToSender(config, cmd,
                "usage: /dashboard bg <url> | /dashboard content <html> | /dashboard clear")
        }
    }

    private fun handleBg(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        if (cmd.args.size < 2) {
            service.replyToSender(config, cmd, "usage: /dashboard bg <url>")
            return
        }
        val url = cmd.args.drop(1).joinToString(" ")
        val destDir = File(service.cacheDir, "dashboard")
        destDir.mkdirs()
        val ext = url.substringAfterLast(".").substringBefore("?").take(4).ifEmpty { "png" }
        val dest = File(destDir, "bg.$ext")

        var downloaded = false
        for (svc in config.services) {
            try {
                if (svc.retrieve(url, dest)) { downloaded = true; break }
            } catch (_: StorageException) {}
        }
        if (!downloaded) {
            downloaded = CmdDownload.rawDownload(url, dest)
        }

        if (downloaded) {
            Dashboard.setBgPath(service, dest.absolutePath)
            service.replyToSender(config, cmd, "Dashboard background set (${dest.length()} bytes)")
        } else {
            service.replyToSender(config, cmd, "Failed to download: $url")
        }
    }

    private fun handleContent(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        if (cmd.args.size < 2) {
            service.replyToSender(config, cmd, "usage: /dashboard content <html>")
            return
        }
        val html = cmd.args.drop(1).joinToString(" ")
        Dashboard.setContent(service, html)
        service.replyToSender(config, cmd, "Dashboard content updated (${html.length} chars)")
    }

    private fun handleClear(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        Dashboard.clear(service)
        service.replyToSender(config, cmd, "Dashboard cleared")
    }
}
