package com.a8s.android

import java.io.File

/**
 * `/rm <path>` — delete a file or empty directory. Refuses to recurse
 * into non-empty directories (the operator can chain a `/ls` first to
 * verify what's there).
 */
object CmdRm {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val pathArg = cmd.args.firstOrNull()?.takeIf { it.isNotBlank() }
        if (pathArg == null) {
            service.replyToOwner(config, cmd.owner, "Usage: /rm <path>")
            return
        }
        try {
            val target = File(pathArg)
            if (!target.exists()) {
                service.replyToOwner(config, cmd.owner, "rm: $pathArg: no such file or directory")
                return
            }
            if (target.isDirectory && (target.listFiles()?.isNotEmpty() == true)) {
                service.replyToOwner(config, cmd.owner, "rm: $pathArg: directory not empty")
                return
            }
            val ok = target.delete()
            if (!ok) {
                service.replyToOwner(config, cmd.owner, "rm: $pathArg: delete failed")
                return
            }
            service.replyToOwner(config, cmd.owner, "rm: $pathArg: deleted")
        } catch (e: Exception) {
            A8sAndroid.log("rm failed: ${e.message}")
            service.replyToOwner(config, cmd.owner, "rm failed: ${e.message}")
        }
    }
}
