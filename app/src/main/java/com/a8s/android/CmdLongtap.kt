package com.a8s.android

/**
 * `/longtap x y [ms]` — synthesize a long press at the given coordinates
 * via the accessibility service. Default duration 800ms.
 */
object CmdLongtap {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToOwner(config, cmd.owner, A11Y_DISABLED_MSG)
            return
        }
        if (cmd.args.size < 2) {
            service.replyToOwner(config, cmd.owner, "Usage: /longtap x y [ms]")
            return
        }
        val x = cmd.args[0].toIntOrNull()
        val y = cmd.args[1].toIntOrNull()
        if (x == null || y == null) {
            service.replyToOwner(config, cmd.owner, "Usage: /longtap x y [ms] (integers)")
            return
        }
        val ms = cmd.args.getOrNull(2)?.toLongOrNull() ?: A11yService.LONG_TAP_MS
        val ok = a11y.longTap(x.toFloat(), y.toFloat(), ms)
        Thread.sleep(POST_GESTURE_SETTLE_MS)
        val text = if (ok) "Long-tapped ($x, $y) for ${ms}ms" else "Long-tap failed"
        UiActionReply.send(service, config, cmd.owner, text, "longtap")
    }
}
