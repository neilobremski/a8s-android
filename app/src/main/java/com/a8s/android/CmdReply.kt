package com.a8s.android

import android.app.RemoteInput as AndroidRemoteInput
import android.content.Intent
import android.os.Bundle

object CmdReply {

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val parts = CmdHelpers.parseReplyArgs(cmd.args)
        if (parts == null) {
            val available = A8sAndroid.listReplySenders()
            val hint = if (available.isEmpty()) "No cached reply actions."
                else "Cached senders: ${available.joinToString(", ")}"
            service.replyToSender(
                config, cmd.sender,
                "usage: /reply <sender> <text>\n$hint",
            )
            return
        }

        val cached = A8sAndroid.getReplyAction(parts.sender)
        if (cached == null) {
            val available = A8sAndroid.listReplySenders()
            val hint = if (available.isEmpty()) "No cached reply actions."
                else "Cached senders: ${available.joinToString(", ")}"
            service.replyToSender(
                config, cmd.sender,
                "No reply action cached for \"${parts.sender}\"\n$hint",
            )
            return
        }

        try {
            val intent = Intent()
            val bundle = Bundle()
            bundle.putCharSequence(cached.remoteInputKey, parts.text)
            AndroidRemoteInput.addResultsToIntent(
                arrayOf(AndroidRemoteInput.Builder(cached.remoteInputKey).build()),
                intent, bundle,
            )
            cached.actionIntent.send(service, 0, intent)
            service.replyToSender(
                config, cmd.sender,
                "Reply sent to ${parts.sender} via notification action: ${parts.text}",
            )
        } catch (e: Exception) {
            service.replyToSender(
                config, cmd.sender,
                "Reply failed: ${e.message}. Action may have expired.",
            )
        }
    }
}
