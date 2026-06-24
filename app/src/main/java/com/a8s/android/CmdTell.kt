package com.a8s.android

/**
 * `/tell <agent> <message>` — publish an a8s envelope over MQTT using the
 * operator's phone principal as `from`.
 */
object CmdTell {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val parts = CmdHelpers.parseTellArgs(cmd.args)
        if (parts == null) {
            service.replyToSender(config, cmd, "usage: /tell <agent> <message>")
            return
        }
        if (cmd.smsReplyTo.isNullOrBlank()) {
            service.replyToSender(config, cmd, "tell failed: SMS origin required (phone principal)")
            return
        }
        val fromAgent = cmd.sender
        if (!config.registry.isPhoneAgent(fromAgent)) {
            service.replyToSender(config, cmd, "tell failed: sender is not a phone-backed agent")
            return
        }
        val (ok, fail) = service.publishEnvelope(fromAgent, parts.agent, parts.message)
        service.replyToSender(
            config, cmd,
            "tell $fromAgent -> ${parts.agent}: ${service.preview(parts.message)} " +
                "(${ok}/${ok + fail} remotes)",
        )
    }
}
