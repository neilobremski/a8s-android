package com.a8s.android

object CmdNicknames {
    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        when (val action = NicknameCommand.parse(cmd.args)) {
            is NicknameCommand.Action.Add -> {
                if (NicknameCommand.conflictsWithCanonicalName(
                        action.nickname,
                        config.device,
                        config.registry.localAgents,
                    )) {
                    service.replyToSender(
                        config,
                        cmd,
                        "Nickname '${action.nickname}' conflicts with a canonical A8S name and cannot override it.",
                    )
                    return
                }
                val stored = NicknamesManager.putNickname(
                    service,
                    action.nickname,
                    action.agent,
                    action.replace,
                )
                val reply = if (stored) {
                    "Nickname saved: ${action.nickname} -> ${action.agent}"
                } else {
                    "Nickname '${action.nickname}' already exists. " +
                        "Use /nicknames replace ${action.nickname} for ${action.agent}."
                }
                service.replyToSender(config, cmd, reply)
            }
            is NicknameCommand.Action.Remove -> {
                NicknamesManager.removeNickname(service, action.nickname)
                service.replyToSender(config, cmd, "Nickname removed: ${action.nickname}")
            }
            is NicknameCommand.Action.ListFor -> listNicknames(service, config, cmd, action.agent)
            is NicknameCommand.Action.SetEnabled -> {
                NicknamesManager.setEnabled(service, action.enabled)
                val state = if (action.enabled) "enabled" else "disabled"
                service.replyToSender(config, cmd, "Nicknames are $state.")
            }
            NicknameCommand.Action.Status -> {
                val state = if (NicknamesManager.isEnabled(service)) "enabled" else "disabled"
                service.replyToSender(config, cmd, "Nicknames are $state.")
            }
            is NicknameCommand.Action.Invalid -> {
                service.replyToSender(config, cmd, "${action.reason}\n${NicknameCommand.USAGE}")
            }
        }
    }

    private fun listNicknames(
        service: A8sService,
        config: A8sAndroid.Config,
        cmd: MqttRoute.Command,
        agent: String?,
    ) {
        val all = NicknamesManager.getAll(service).toSortedMap()
        val filtered = if (agent == null) all else all.filterValues { it == agent }
        val reply = when {
            filtered.isEmpty() && agent == null -> "No nicknames configured."
            filtered.isEmpty() -> "No nicknames configured for $agent."
            else -> "Nicknames:\n" + filtered.entries.joinToString("\n") { "${it.key} -> ${it.value}" }
        }
        service.replyToSender(config, cmd, reply)
    }
}
