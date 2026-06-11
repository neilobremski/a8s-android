package com.a8s.android

/**
 * `/tell <agent> <message>` — publish an a8s envelope over MQTT using the
 * operator's opaque SMS sub-identity as `from` (issue #38).
 */
object CmdTell {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val parts = CmdHelpers.parseTellArgs(cmd.args)
        if (parts == null) {
            service.replyToSender(config, cmd, "usage: /tell <agent> <message>")
            return
        }
        val replyNumber = cmd.smsReplyTo
        if (replyNumber.isNullOrBlank()) {
            service.replyToSender(config, cmd, "tell failed: SMS origin required for sub-identity")
            return
        }
        val fromIdentity = try {
            PhoneNormalize.buildSmsSubIdentity(config.tellPrefix, replyNumber)
        } catch (e: IllegalArgumentException) {
            service.replyToSender(config, cmd, "tell failed: ${e.message}")
            return
        }
        val (ok, fail) = service.publishEnvelope(fromIdentity, parts.agent, parts.message)
        service.replyToSender(
            config, cmd,
            "tell $fromIdentity -> ${parts.agent}: ${service.preview(parts.message)} " +
                "(${ok}/${ok + fail} remotes)",
        )
    }
}
