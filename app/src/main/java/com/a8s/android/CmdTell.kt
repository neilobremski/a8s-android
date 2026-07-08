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
        val remoteNote = "(${ok}/${ok + fail} remotes)"
        val confirm = if (!cmd.smsReplyTo.isNullOrBlank()) {
            // Keep SMS confirmation short — repeating the told message causes
            // multipart replies whose carrier echoes loop back as inbound SMS.
            "tell $fromAgent -> ${parts.agent}: ok (${parts.message.length} chars) $remoteNote"
        } else {
            "tell $fromAgent -> ${parts.agent}: ${service.preview(parts.message)} $remoteNote"
        }
        service.replyToSender(config, cmd, confirm)
    }
}
