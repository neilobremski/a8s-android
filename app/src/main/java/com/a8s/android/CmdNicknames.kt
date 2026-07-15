package com.a8s.android

object CmdNicknames {
    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        if (cmd.args.size >= 3) {
            val realAgent = cmd.args[0]
            val action = cmd.args[1].lowercase()
            val nickname = cmd.args.drop(2).joinToString(" ")
            
            when (action) {
                "add" -> {
                    NicknamesManager.addNickname(service, realAgent, nickname)
                    service.replyToSender(config, cmd, "Added nickname: $nickname -> $realAgent")
                    return
                }
                "remove", "rm" -> {
                    NicknamesManager.removeNickname(service, nickname)
                    service.replyToSender(config, cmd, "Removed nickname: $nickname")
                    return
                }
                else -> {
                    service.replyToSender(config, cmd, "usage: /nicknames <agent> add|rm <nickname>")
                    return
                }
            }
        } else if (cmd.args.isNotEmpty()) {
            service.replyToSender(config, cmd, "usage: /nicknames <agent> add|rm <nickname>")
            return
        }
        
        val all = NicknamesManager.getAll(service)
        val reply = if (all.isEmpty()) {
            "No nicknames configured."
        } else {
            "Nicknames:\n" + all.entries.joinToString("\n") { "${it.key} -> ${it.value}" }
        }
        service.replyToSender(config, cmd, reply)
    }
}
