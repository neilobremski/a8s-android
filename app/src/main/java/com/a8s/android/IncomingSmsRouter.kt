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

    private val lastTellTarget = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun setLastTellTarget(senderAgent: String, targetAgent: String) {
        lastTellTarget[senderAgent] = targetAgent
    }

    internal fun getLastTellTarget(senderAgent: String): String? = lastTellTarget[senderAgent]

    private data class ResolvedSender(val number: String, val principal: Principal)

    data class IngressMeta(
        val eventTimeMs: Long? = null,
        val maxAgeMs: Long? = null,
    )

    data class IncomingMessage(
        val fromIdentity: String,
        val body: String,
        val mediaFiles: List<File> = emptyList(),
        val replyAction: android.app.Notification.Action? = null,
        val ingress: IngressMeta? = null,
    )

    fun publishIncoming(service: A8sService, message: IncomingMessage) {
        val config = A8sAndroid.config ?: return
        val eventTimeMs = message.ingress?.eventTimeMs
        val maxAgeMs = message.ingress?.maxAgeMs
        if (eventTimeMs != null && maxAgeMs != null &&
            IngressStaleness.isTooOld(eventTimeMs, maxAgeMs = maxAgeMs)) {
            A8sAndroid.log(
                "Ignored stale ingress from ${message.fromIdentity} " +
                    "(event ${ageMinutes(eventTimeMs)}m ago, max ${maxAgeMs / 60_000}m)",
            )
            return
        }
        val sender = resolveSender(service, config, message.fromIdentity) ?: return

        if (isOutboundSmsEcho(sender.number, message.body)) {
            A8sAndroid.log(
                "Ignored inbound SMS echo from ${PhoneNormalize.maskNumber(sender.number)} " +
                    "(multipart outbound fragment)",
            )
            return
        }

        if (message.replyAction != null && sender.number.isNotBlank()) {
            A8sAndroid.cacheReplyAction(sender.number, message.replyAction)
        }

        if (handleSmsCommand(service, config, sender, message.body)) return

        publishFallThrough(service, config, sender, message.body, message.mediaFiles)
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

    private fun ageMinutes(eventTimeMs: Long): Long =
        (System.currentTimeMillis() - eventTimeMs) / 60_000L

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
        sender: ResolvedSender,
        body: String,
        mediaFiles: List<File>,
    ) {
        val toAgent = lastTellTarget[sender.principal.agent]
        if (toAgent == null) {
            val msg = "No default agent set up. This message can't be delivered. " +
                "Use /tell <agent> <message> to set your active agent."
            A8sAndroid.log("SMS fall-through from ${sender.principal.agent} rejected (no last /tell target)")
            service.sendSms(sender.number, msg)
            mediaFiles.forEach { it.delete() }
            return
        }
        if (mediaFiles.isNotEmpty()) {
            Thread {
                val filesArr = service.buildFilesArray(config, mediaFiles)
                val outbound = OutboundSms(sender.principal.agent, toAgent, body, filesArr)
                publishOne(service, config, outbound)
                mediaFiles.forEach { it.delete() }
            }.start()
        } else {
            val outbound = OutboundSms(sender.principal.agent, toAgent, body, JSONArray())
            publishOne(service, config, outbound)
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
