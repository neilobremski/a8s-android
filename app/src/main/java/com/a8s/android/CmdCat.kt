package com.a8s.android

import java.io.File

/**
 * `/cat <path>` — read a file. Small text files (<= 10 KB and
 * passes a printable-bytes heuristic) are returned inline; everything
 * else is attached via `publishToOwner(... files=...)`.
 */
object CmdCat {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val pathArg = cmd.args.firstOrNull()?.takeIf { it.isNotBlank() }
        if (pathArg == null) {
            service.replyToSender(config, cmd, "Usage: /cat <path>")
            return
        }
        try {
            val target = File(pathArg)
            if (!target.exists()) {
                service.replyToSender(config, cmd, "cat: $pathArg: no such file")
                return
            }
            if (!target.isFile) {
                service.replyToSender(config, cmd, "cat: $pathArg: not a regular file")
                return
            }
            val size = target.length()
            if (size <= CmdHelpers.CAT_INLINE_LIMIT_BYTES) {
                val bytes = target.readBytes()
                if (CmdHelpers.looksLikeText(bytes)) {
                    val content = String(bytes, Charsets.UTF_8)
                    service.replyToSender(
                        config, cmd,
                        "$pathArg (${CmdHelpers.humanSize(size)})\n$content",
                    )
                    return
                }
            }
            // Either too big for inline or detected as binary — send as attachment.
            service.replyToSender(
                config, cmd,
                "$pathArg (${CmdHelpers.humanSize(size)})",
                files = listOf(target),
            )
        } catch (e: Exception) {
            A8sAndroid.log("cat failed: ${e.message}")
            service.replyToSender(config, cmd, "cat failed: ${e.message}")
        }
    }
}
