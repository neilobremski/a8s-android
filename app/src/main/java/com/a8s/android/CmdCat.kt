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
            service.replyToOwner(config, cmd.owner, "Usage: /cat <path>")
            return
        }
        try {
            val target = File(pathArg)
            if (!target.exists()) {
                service.replyToOwner(config, cmd.owner, "cat: $pathArg: no such file")
                return
            }
            if (!target.isFile) {
                service.replyToOwner(config, cmd.owner, "cat: $pathArg: not a regular file")
                return
            }
            val size = target.length()
            if (size <= CmdHelpers.CAT_INLINE_LIMIT_BYTES) {
                val bytes = target.readBytes()
                if (CmdHelpers.looksLikeText(bytes)) {
                    val content = String(bytes, Charsets.UTF_8)
                    service.replyToOwner(
                        config, cmd.owner,
                        "$pathArg (${CmdHelpers.humanSize(size)})\n$content",
                    )
                    return
                }
            }
            // Either too big for inline or detected as binary — send as attachment.
            service.replyToOwner(
                config, cmd.owner,
                "$pathArg (${CmdHelpers.humanSize(size)})",
                files = listOf(target),
            )
        } catch (e: Exception) {
            A8sAndroid.log("cat failed: ${e.message}")
            service.replyToOwner(config, cmd.owner, "cat failed: ${e.message}")
        }
    }
}
