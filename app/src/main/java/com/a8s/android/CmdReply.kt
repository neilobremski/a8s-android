package com.a8s.android

import android.app.RemoteInput as AndroidRemoteInput
import android.content.Intent
import android.os.Bundle

object CmdReply {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        if (cmd.args.size < 2) {
            val available = A8sAndroid.listReplySenders()
            val hint = if (available.isEmpty()) "No cached reply actions."
                else "Cached numbers: ${available.joinToString(", ")}"
            service.replyToSender(
                config, cmd.sender,
                "usage: /reply <phone-number> <text>\n$hint",
            )
            return
        }

        val number = cmd.args[0]
        val text = cmd.args.drop(1).joinToString(" ")
        val cached = A8sAndroid.getReplyAction(number)
            ?: A8sAndroid.getReplyActionByDigits(number)
        if (cached == null) {
            val available = A8sAndroid.listReplySenders()
            val hint = if (available.isEmpty()) "No cached reply actions."
                else "Cached numbers: ${available.joinToString(", ")}"
            service.replyToSender(
                config, cmd.sender,
                "No reply action cached for $number\n$hint",
            )
            return
        }

        try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(cached.remoteInputKey, text)
            AndroidRemoteInput.addResultsToIntent(
                arrayOf(AndroidRemoteInput.Builder(cached.remoteInputKey).build()),
                intent, bundle,
            )
            cached.actionIntent.send(service, 0, intent)
            service.replyToSender(
                config, cmd.sender,
                "Reply sent to $number via notification action: $text",
            )
        } catch (e: Exception) {
            service.replyToSender(
                config, cmd.sender,
                "Reply failed: ${e.message}. Action may have expired.",
            )
        }
    }
}
