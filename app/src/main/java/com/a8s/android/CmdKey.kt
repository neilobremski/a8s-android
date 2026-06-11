package com.a8s.android

/**
 * `/key <NAME>` — perform a global accessibility action by name.
 * Accepts: BACK, HOME, RECENTS, NOTIFICATIONS, QUICK_SETTINGS, LOCK_SCREEN
 * (case-insensitive).
 */
object CmdKey {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToSender(config, cmd, A11Y_DISABLED_MSG)
            return
        }
        val name = cmd.args.firstOrNull()?.takeIf { it.isNotBlank() }
        if (name == null) {
            service.replyToSender(
                config, cmd,
                "Usage: /key <BACK|HOME|RECENTS|NOTIFICATIONS|QUICK_SETTINGS|LOCK_SCREEN>",
            )
            return
        }
        val ok = a11y.globalAction(name)
        Thread.sleep(POST_GESTURE_SETTLE_MS)
        val canonical = name.uppercase()
        val text = if (ok) "Sent key $canonical" else "Key failed: unknown or rejected '$name'"
        UiActionReply.send(service, config, cmd, text, "key")
    }
}
