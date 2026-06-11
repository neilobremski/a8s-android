package com.a8s.android

/**
 * Inbound slash-command dispatch with SMS dedup gate (issue #36).
 * Lives outside [A8sService] to keep that class under detekt's LargeClass limit.
 */
object CommandDispatch {

    fun handle(route: MqttRoute.Command, execute: (MqttRoute.Command) -> Unit) {
        if (!gateInboundSmsCommand(route)) {
            A8sAndroid.log(
                "/${route.name} duplicate dropped (id=${route.envelopeId.take(8).ifEmpty { "?" }}…)",
            )
            return
        }
        A8sAndroid.log("/${route.name} from sender=${route.sender}")
        execute(route)
    }
}
