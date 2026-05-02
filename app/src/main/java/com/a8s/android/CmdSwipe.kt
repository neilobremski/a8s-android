package com.a8s.android

/**
 * `/swipe x1 y1 x2 y2 [ms]` — synthesize a straight-line swipe from
 * (x1, y1) to (x2, y2) over `ms` milliseconds (default 300ms).
 */
object CmdSwipe {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToOwner(config, cmd.owner, A11Y_DISABLED_MSG)
            return
        }
        if (cmd.args.size < 4) {
            service.replyToOwner(config, cmd.owner, "Usage: /swipe x1 y1 x2 y2 [ms]")
            return
        }
        val coords = (0..3).map { cmd.args[it].toIntOrNull() }
        if (coords.any { it == null }) {
            service.replyToOwner(config, cmd.owner, "Usage: /swipe x1 y1 x2 y2 [ms] (integers)")
            return
        }
        val x1 = coords[0]!!
        val y1 = coords[1]!!
        val x2 = coords[2]!!
        val y2 = coords[3]!!
        val ms = cmd.args.getOrNull(4)?.toLongOrNull() ?: A11yService.SWIPE_MS
        val ok = a11y.swipe(x1.toFloat(), y1.toFloat(), x2.toFloat(), y2.toFloat(), ms)
        Thread.sleep(POST_GESTURE_SETTLE_MS)
        val text = if (ok) "Swiped ($x1,$y1) -> ($x2,$y2) over ${ms}ms" else "Swipe failed"
        UiActionReply.send(service, config, cmd.owner, text, "swipe")
    }
}
