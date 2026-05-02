package com.a8s.android

/**
 * `/find <label>` — walk the active window's accessibility tree, find a
 * node whose `text` or `contentDescription` contains the label
 * (case-insensitive), and click it. Useful for label-driven automation
 * where pixel coordinates aren't known ahead of time.
 */
object CmdFind {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToSender(config, cmd.sender, A11Y_DISABLED_MSG)
            return
        }
        val label = cmd.args.joinToString(" ").trim()
        if (label.isEmpty()) {
            service.replyToSender(config, cmd.sender, "Usage: /find <label>")
            return
        }
        val ok = a11y.findAndTap(label)
        Thread.sleep(POST_GESTURE_SETTLE_MS)
        val msg = if (ok) "Tapped node \"$label\"" else "Find failed: no node matches \"$label\""
        UiActionReply.send(service, config, cmd.sender, msg, "find")
    }
}
