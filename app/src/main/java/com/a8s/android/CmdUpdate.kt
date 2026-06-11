package com.a8s.android

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

object CmdUpdate {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val checkOnly = cmd.args.any { it == "--check" || it == "-c" }
        val explicitUrl = cmd.args.firstOrNull { it.startsWith("http://") || it.startsWith("https://") }
        try {
            val installedVersion = installedVersionName(service)
            if (checkOnly) {
                val latest = Updater.fetchLatestRelease()
                service.replyToSender(config, cmd, Updater.renderCheck(installedVersion, latest))
                return
            }
            val (downloadUrl, fileName) = if (explicitUrl != null) {
                Pair(explicitUrl, "explicit-${System.currentTimeMillis()}.apk")
            } else {
                val latest = Updater.fetchLatestRelease()
                if (Updater.compareVersions(installedVersion, latest.versionName) >= 0) {
                    service.replyToSender(
                        config, cmd,
                        "Already up to date (installed v$installedVersion, latest ${latest.tagName}). " +
                            "Use /update <url> to force a specific build.",
                    )
                    return
                }
                service.replyToSender(
                    config, cmd,
                    "Update available: v$installedVersion → ${latest.tagName} " +
                        "(${Updater.humanSize(latest.sizeBytes)}). Downloading…",
                )
                Pair(latest.apkUrl, latest.apkName)
            }
            val dest = File(File(service.cacheDir, "updates"), fileName)
            Updater.downloadTo(downloadUrl, dest)
            A8sAndroid.log("Update downloaded to ${dest.absolutePath} (${dest.length()} bytes)")
            triggerInstallPrompt(service, dest)
            service.replyToSender(
                config, cmd,
                "Downloaded ${Updater.humanSize(dest.length())}. Install dialog launched on phone — " +
                    "tap Install on the device to apply.",
            )
        } catch (e: Exception) {
            A8sAndroid.log("Update failed: ${e.message}")
            service.replyToSender(config, cmd, "Update failed: ${e.message}")
        }
    }

    fun installedVersionName(service: A8sService): String = try {
        service.packageManager.getPackageInfo(service.packageName, 0).versionName ?: "?"
    } catch (e: Exception) {
        "?"
    }

    fun triggerInstallPrompt(service: A8sService, apk: File) {
        val authority = "${service.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(service, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        service.startActivity(intent)
    }
}
