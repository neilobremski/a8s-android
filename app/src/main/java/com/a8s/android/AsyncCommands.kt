package com.a8s.android

/**
 * Verb → handler map for async slash commands. Kept outside [A8sService]
 * to stay under detekt's LargeClass limit.
 */
object AsyncCommands {

    val handlers: Map<String, (A8sService, A8sAndroid.Config, MqttRoute.Command) -> Unit> = mapOf(
        "update" to { s, c, k -> CmdUpdate.run(s, c, k) },
        "screenshot" to { s, c, k -> CmdScreenshot.run(s, c, k) },
        "photo" to { s, c, k -> CmdPhoto.run(s, c, k) },
        "video" to { s, c, k -> CmdVideo.run(s, c, k) },
        "audio" to { s, c, k -> CmdAudio.run(s, c, k) },
        "location" to { s, c, k -> CmdLocation.run(s, c, k) },
        "say" to { s, c, k -> CmdSay.run(s, c, k) },
        "notify" to { s, c, k -> CmdNotify.run(s, c, k) },
        "ls" to { s, c, k -> CmdLs.run(s, c, k) },
        "cat" to { s, c, k -> CmdCat.run(s, c, k) },
        "rm" to { s, c, k -> CmdRm.run(s, c, k) },
        "tap" to { s, c, k -> CmdTap.run(s, c, k) },
        "longtap" to { s, c, k -> CmdLongtap.run(s, c, k) },
        "swipe" to { s, c, k -> CmdSwipe.run(s, c, k) },
        "key" to { s, c, k -> CmdKey.run(s, c, k) },
        "input" to { s, c, k -> CmdInput.run(s, c, k) },
        "find" to { s, c, k -> CmdFind.run(s, c, k) },
        "macro" to { s, c, k -> CmdMacro.run(s, c, k) },
        "send" to { s, c, k ->
            val parts = CmdHelpers.parseSendArgs(k.args)
            if (parts == null) {
                s.replyToSender(c, k, "usage: /send <number> <message>")
            } else {
                Thread {
                    val body = SmsCommandDelivery.smsBodyWithUploads(s, c, parts.body, emptyList(), existingEnvelopeFiles = k.files)
                    s.sendSms(parts.number, body)
                    s.replyToSender(c, k, "SMS queued to ${parts.number}: ${s.preview(body)}")
                }.start()
            }
        },
        "mms" to { s, c, k -> CmdMms.run(s, c, k) },
        "reply" to { s, c, k -> CmdReply.run(s, c, k) },
        "tell" to { s, c, k -> CmdTell.run(s, c, k) },
        "download" to { s, c, k -> CmdDownload.run(s, c, k) },
        "dashboard" to { s, c, k -> CmdDashboard.run(s, c, k) },
        "config" to { s, c, k -> CmdConfig.run(s, c, k) },
    )
}
