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
        val resolution = NicknamesManager.resolveDetailed(service, parts.rawAgent)
        val resolutionKind = when {
            !resolution.enabled -> "disabled"
            resolution.matched -> "matched"
            else -> "not-matched"
        }
        A8sAndroid.log(
            "TELL resolve raw=${resolution.input} normalized=${resolution.normalized} " +
                "resolved=${resolution.resolved} nickname=$resolutionKind",
        )
        val result = service.publishEnvelope(fromAgent, resolution.resolved, parts.message)
        val status = when {
            result.total == 0 -> TransactionTrace.Status.FAIL
            result.accepted == result.total -> TransactionTrace.Status.OK
            result.accepted > 0 -> TransactionTrace.Status.PARTIAL
            else -> TransactionTrace.Status.FAIL
        }
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = result.envelopeId,
                flow = "TELL_OUT",
                status = status,
                from = fromAgent,
                to = resolution.resolved,
                summary = "nickname $resolutionKind; ${result.accepted}/${result.total} remote(s) accepted",
                detail = "raw target: ${resolution.input}\nnormalized target: ${resolution.normalized}",
            ),
        )
        if (result.accepted > 0) {
            IncomingSmsRouter.setLastTellTarget(service, fromAgent, resolution.resolved)
        } else {
            service.replyToSender(config, cmd, "tell failed: 0 remotes reached")
        }
    }
}
