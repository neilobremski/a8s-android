package com.a8s.android

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMS/RCS → MQTT publish path and SMS-originated slash commands.
 */
object IncomingSmsRouter {

    private data class ResolvedSender(val number: String, val principal: Principal)

    fun publishIncoming(
        service: A8sService,
        fromIdentity: String,
        body: String,
        mediaFiles: List<File> = emptyList(),
        replyAction: android.app.Notification.Action? = null,
    ) {
        val config = A8sAndroid.config ?: return
        val sender = resolveSender(service, config, fromIdentity) ?: return

        if (replyAction != null && sender.number.isNotBlank()) {
            A8sAndroid.cacheReplyAction(sender.number, replyAction)
        }

        if (handleSmsCommand(service, config, sender, body)) return

        publishFallThrough(service, config, sender.principal, body, mediaFiles)
    }

    private fun resolveSender(
        service: A8sService,
        config: A8sAndroid.Config,
        fromIdentity: String,
    ): ResolvedSender? {
        config.registry.principalByPhone(fromIdentity)?.let { principal ->
            return ResolvedSender(fromIdentity, principal)
        }
        val resolved = phoneNumberForDisplayName(service, fromIdentity)
        if (resolved.isNullOrBlank()) {
            A8sAndroid.log("Ignored incoming from $fromIdentity (no phone number resolved)")
            return null
        }
        val principal = config.registry.principalByPhone(resolved)
        if (principal == null) {
            A8sAndroid.log(
                "Ignored incoming from $fromIdentity " +
                    "(resolved to ${PhoneNormalize.maskNumber(resolved)}, not a configured phone principal)",
            )
            return null
        }
        return ResolvedSender(resolved, principal)
    }

    private fun handleSmsCommand(
        service: A8sService,
        config: A8sAndroid.Config,
        sender: ResolvedSender,
        body: String,
    ): Boolean = when (val result = SmsSlashCommand.classify(sender.number, body, config)) {
        is SmsSlashCommand.Result.Authorized -> {
            if (gateSmsOriginCommand(result.principal.agent, body)) {
                A8sAndroid.log(
                    "SMS command /${result.command.name} from ${result.principal.agent} " +
                        "(${PhoneNormalize.maskNumber(sender.number)})",
                )
                CommandDispatch.handle(result.command, service::executeCommand)
            } else {
                A8sAndroid.log("SMS command /${result.command.name} ignored (duplicate)")
            }
            true
        }
        is SmsSlashCommand.Result.Forbidden -> {
            A8sAndroid.log(
                "SMS command /${result.verb} from ${result.agent} " +
                    "(${PhoneNormalize.maskNumber(sender.number)}) rejected (not permitted)",
            )
            service.sendSms(result.replyNumber, "'/${result.verb}' is not permitted over SMS.")
            true
        }
        SmsSlashCommand.Result.NotForSms -> false
    }

    private data class OutboundSms(val fromAgent: String, val toAgent: String, val body: String, val filesArr: JSONArray)

    private fun publishFallThrough(
        service: A8sService,
        config: A8sAndroid.Config,
        principal: Principal,
        body: String,
        mediaFiles: List<File>,
    ) {
        val toAgent = config.routing.smsInboundAgent
        if (mediaFiles.isNotEmpty()) {
            Thread {
                val filesArr = service.buildFilesArray(config, mediaFiles)
                publishOne(service, config, OutboundSms(principal.agent, toAgent, body, filesArr))
                mediaFiles.forEach { it.delete() }
            }.start()
        } else {
            publishOne(service, config, OutboundSms(principal.agent, toAgent, body, JSONArray()))
        }
    }

    private fun publishOne(service: A8sService, config: A8sAndroid.Config, outbound: OutboundSms) {
        val dedupKey = "${outbound.fromAgent}|${outbound.toAgent}|${outbound.body}"
        if (!service.publishDedup.shouldPublish(dedupKey)) {
            A8sAndroid.log("Skipping duplicate to ${outbound.toAgent} (already sent recently)")
            return
        }
        val payload = buildIncomingPayload(outbound.fromAgent, outbound.toAgent, outbound.body, outbound.filesArr)
        val (ok, fail) = service.publishToAllRemotes(config, payload)
        val fileNote = if (outbound.filesArr.length() > 0) "[+${outbound.filesArr.length()} file(s)] " else ""
        A8sAndroid.log(
            "SMS -> MQTT ${outbound.fromAgent} -> ${outbound.toAgent}: " +
                "${service.preview(outbound.body)} $fileNote(${ok}/${ok + fail} remotes)",
        )
    }

    private fun buildIncomingPayload(
        fromAgent: String,
        toAgent: String,
        body: String,
        filesArr: JSONArray,
    ): String = JSONObject().apply {
        put("id", Ulid.new())
        put("date", EnvelopeTime.isoNowUtc())
        put("from", fromAgent)
        put("to", toAgent)
        put("content", body)
        put("files", filesArr)
    }.toString()

    private fun phoneNumberForDisplayName(service: A8sService, name: String): String? {
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED) {
            A8sAndroid.log("Cannot resolve $name: READ_CONTACTS not granted")
            return null
        }
        return try {
            service.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} = ?",
                arrayOf(name),
                null,
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            A8sAndroid.log("Contacts lookup failed for $name: ${e.message}")
            null
        }
    }
}
