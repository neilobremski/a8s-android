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
        val canonicalNames = config.registry.localAgents + config.device
        val resolution = NicknamesManager.resolveDetailed(service, parts.rawAgent, canonicalNames)
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
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = result.envelopeId,
                flow = "TELL_OUT",
                status = deliveryStatus(result),
                from = fromAgent,
                to = resolution.resolved,
                summary = "nickname $resolutionKind; ${deliverySummary(result)}",
                detail = "raw target: ${resolution.input}\nnormalized target: ${resolution.normalized}",
            ),
        )
        if (result.accepted > 0 || result.failed > 0) {
            IncomingSmsRouter.setLastTellTarget(service, fromAgent, resolution.resolved)
        }
        if (result.total == 0) {
            service.replyToSender(config, cmd, "tell failed: no MQTT remotes configured")
        } else if (result.accepted == 0) {
            service.replyToSender(config, cmd, "tell queued: MQTT disconnected; will retry")
        }
    }

    private fun deliveryStatus(result: EnvelopePublishResult): TransactionTrace.Status = when {
        result.total == 0 -> TransactionTrace.Status.FAIL
        result.accepted == 0 -> TransactionTrace.Status.PARTIAL
        result.accepted == result.total -> TransactionTrace.Status.OK
        else -> TransactionTrace.Status.PARTIAL
    }

    private fun deliverySummary(result: EnvelopePublishResult): String = when {
        result.total == 0 -> "no MQTT remotes configured"
        result.accepted == 0 -> "queued for retry; no MQTT remotes connected"
        result.failed > 0 -> "${result.accepted}/${result.total} remote(s) accepted; ${result.failed} queued"
        else -> "${result.accepted}/${result.total} remote(s) accepted"
    }
}
