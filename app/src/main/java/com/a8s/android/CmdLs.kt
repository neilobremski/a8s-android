package com.a8s.android

import java.io.File

/**
 * `/ls [<path>]` — list directory entries. Defaults to
 * `/sdcard/Download`. Plain-text reply formatted by
 * `CmdHelpers.renderLs`.
 */
object CmdLs {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val pathArg = cmd.args.firstOrNull()?.takeIf { it.isNotBlank() } ?: CmdHelpers.LS_DEFAULT_PATH
        try {
            val target = File(pathArg)
            if (!target.exists()) {
                service.replyToSender(config, cmd.sender, "ls: $pathArg: no such file or directory")
                return
            }
            if (!target.isDirectory) {
                service.replyToSender(config, cmd.sender, "ls: $pathArg: not a directory")
                return
            }
            val children = target.listFiles()?.toList().orEmpty()
            val entries = children.map {
                CmdHelpers.LsEntry(
                    name = it.name,
                    size = if (it.isFile) it.length() else 0L,
                    lastModifiedMs = it.lastModified(),
                    isDirectory = it.isDirectory,
                )
            }
            service.replyToSender(config, cmd.sender, CmdHelpers.renderLs(target.canonicalPath, entries))
        } catch (e: Exception) {
            A8sAndroid.log("ls failed: ${e.message}")
            service.replyToSender(config, cmd.sender, "ls failed: ${e.message}")
        }
    }
}
