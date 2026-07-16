package com.a8s.android

import org.json.JSONObject

/** Inbound MQTT dispatch + transaction trace (keeps [A8sService] under detekt limits). */
object MqttInboundHandler {

    fun handle(service: A8sService, json: JSONObject, config: A8sAndroid.Config) {
        val txnId = json.optString("id")
        val from = json.optString("from")
        val to = json.optString("to")
        val fileSummary = TransactionTrace.summarizeEnvelopeFiles(json)

        PhoneAgentRoute.evaluate(json, config).let { phoneResult ->
            when (phoneResult) {
                is PhoneAgentRoute.Result.Ok -> {
                    TransactionTrace.record(
                        TransactionTrace.Event(
                            txnId = txnId,
                            flow = "MQTT_IN",
                            status = TransactionTrace.Status.OK,
                            from = from,
                            to = TransactionTrace.maskTo(to),
                            summary = "routed to phone-agent SMS forward",
                            detail = fileSummary,
                        ),
                    )
                    SmsCommandDelivery.forwardToSms(service, phoneResult.forward)
                    return
                }
                is PhoneAgentRoute.Result.Denied -> {
                    val reason = "from=$from not permitted to forward to ${phoneResult.targetAgent}"
                    A8sAndroid.log("MQTT -> drop ($reason)")
                    recordDrop(txnId, from, to, reason, fileSummary)
                    return
                }
                PhoneAgentRoute.Result.NotApplicable -> Unit
            }
        }
        when (val route = decideRoute(json, config)) {
            is MqttRoute.NotACommand -> {
                recordDrop(txnId, from, to, "not a slash command", fileSummary)
                val reply = "error: message must start with a /command\n" +
                    "known: " + CmdHelpers.KNOWN_COMMANDS.joinToString(", ")
                service.publishToSender(config, route.sender, reply)
            }
            is MqttRoute.Command -> {
                recordCommand(txnId, from, to, route)
                CommandDispatch.handle(service, route, service::executeCommand)
            }
            is MqttRoute.Drop -> {
                if (route.reason.startsWith("self-loopback")) {
                    recordLoopback(txnId, from, to, fileSummary)
                } else {
                    A8sAndroid.log("MQTT -> drop (${route.reason})")
                    recordDrop(txnId, from, to, route.reason, fileSummary)
                }
            }
            is MqttRoute.ParseError -> {
                A8sAndroid.log("MQTT Handle Error: ${route.reason}")
                TransactionTrace.record(
                    TransactionTrace.Event(
                        txnId = txnId,
                        flow = "MQTT_IN",
                        status = TransactionTrace.Status.FAIL,
                        from = from,
                        to = to,
                        summary = route.reason,
                        detail = fileSummary,
                    ),
                )
            }
        }
    }

    private fun recordLoopback(txnId: String, from: String, to: String, fileSummary: String) {
        A8sAndroid.log(
            "MQTT broker loopback observed id=${txnId.take(8).ifEmpty { "?" }} from=$from to=$to",
        )
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = txnId,
                flow = "MQTT_LOOP",
                status = TransactionTrace.Status.OK,
                from = from,
                to = to,
                summary = "broker returned self-published envelope",
                detail = fileSummary,
            ),
        )
    }

    private fun recordDrop(txnId: String, from: String, to: String, reason: String, fileSummary: String) {
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = txnId,
                flow = "MQTT_IN",
                status = TransactionTrace.Status.DROP,
                from = from,
                to = to,
                summary = reason,
                detail = fileSummary,
            ),
        )
    }

    private fun recordCommand(txnId: String, from: String, to: String, route: MqttRoute.Command) {
        val fileDetail = if (route.files.isEmpty()) {
            "files: none"
        } else {
            route.files.joinToString("\n") { ef ->
                val n = ef.storageUrls.size
                if (n == 0) "  • ${ef.filename}: NO storage urls"
                else "  • ${ef.filename}: $n url(s)"
            }
        }
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = txnId,
                flow = "MQTT_CMD",
                status = TransactionTrace.Status.OK,
                from = from,
                to = to,
                summary = "/${route.name} from ${route.sender}",
                detail = fileDetail,
            ),
        )
    }
}
