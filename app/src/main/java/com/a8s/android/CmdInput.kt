package com.a8s.android

/**
 * `/input <text>` — write text into the currently-focused input field
 * via `AccessibilityNodeInfo.ACTION_SET_TEXT`. Reports failure if no
 * field is focused (rather than throwing).
 */
object CmdInput {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToOwner(config, cmd.owner, A11Y_DISABLED_MSG)
            return
        }
        val text = cmd.args.joinToString(" ").trim()
        if (text.isEmpty()) {
            service.replyToOwner(config, cmd.owner, "Usage: /input <text>")
            return
        }
        val ok = a11y.inputText(text)
        Thread.sleep(POST_GESTURE_SETTLE_MS)
        val msg = if (ok) "Typed: \"$text\"" else "Input failed: no field focused"
        UiActionReply.send(service, config, cmd.owner, msg, "input")
    }
}
