package com.a8s.android

/**
 * `/tap x y` — synthesize a tap at the given screen coordinates via the
 * accessibility service, then attach a post-tap screenshot so the
 * caller can verify the resulting UI state.
 */
object CmdTap {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToSender(config, cmd.sender, A11Y_DISABLED_MSG)
            return
        }
        if (cmd.args.size < 2) {
            service.replyToSender(config, cmd.sender, "Usage: /tap x y")
            return
        }
        val x = cmd.args[0].toIntOrNull()
        val y = cmd.args[1].toIntOrNull()
        if (x == null || y == null) {
            service.replyToSender(config, cmd.sender, "Usage: /tap x y (integers)")
            return
        }
        val ok = a11y.tap(x.toFloat(), y.toFloat())
        Thread.sleep(POST_GESTURE_SETTLE_MS)
        val text = if (ok) "Tapped ($x, $y)" else "Tap failed"
        UiActionReply.send(service, config, cmd.sender, text, "tap")
    }
}
