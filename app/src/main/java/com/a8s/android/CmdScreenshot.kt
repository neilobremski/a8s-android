package com.a8s.android

import android.content.Context
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import java.io.File

object CmdScreenshot {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val data = service.projectionData
        if (data == null || service.projectionResultCode == 0) {
            service.replyToSender(
                config, cmd,
                "Screen capture not authorized — consent is held in-memory and " +
                    "is lost on app/process restart (e.g. after /update reinstall). " +
                    "Open the app and tap \"Grant All Permissions\" (or " +
                    "\"Enable Screen Capture (for /screenshot)\") to re-grant.",
            )
            return
        }
        if (config.services.isEmpty()) {
            service.replyToSender(
                config, cmd,
                "Cannot send screenshot: no storage service configured. " +
                    "Add a `services` entry to a8s.json (e.g. tempfile_org).",
            )
            return
        }
        val mgr = service.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        var projection: MediaProjection? = null
        try {
            projection = mgr.getMediaProjection(service.projectionResultCode, data)
            if (projection == null) {
                service.replyToSender(config, cmd, "Screen capture failed: projection unavailable")
                return
            }
            val dest = File(File(service.cacheDir, "screenshots"), "screenshot-${System.currentTimeMillis()}.png")
            val captured = Screenshot(service, projection).capture(dest)
            if (!captured) {
                service.replyToSender(config, cmd, "Screen capture failed: timed out waiting for frame")
                return
            }
            A8sAndroid.log("Screenshot captured: ${dest.length()} bytes")
            service.replyToSender(
                config, cmd,
                "Screenshot (${dest.length()} bytes)",
                files = listOf(dest),
            )
        } catch (e: Exception) {
            A8sAndroid.log("Screenshot failed: ${e.message}")
            service.replyToSender(config, cmd, "Screenshot failed: ${e.message}")
        } finally {
            try { projection?.stop() } catch (_: Exception) { }
        }
    }
}
