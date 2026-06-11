package com.a8s.android

import android.Manifest
import android.content.pm.PackageManager
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMS/RCS → MQTT publish path and SMS-originated slash commands (#38).
 * Extracted from [A8sService] for detekt LargeClass.
 */
object IncomingSmsRouter {

    /** A resolved phonebook sender: their number plus every matching name. */
    private data class Sender(val number: String, val names: Set<String>)

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

        if (handleSmsCommand(service, config, sender.number, body)) return

        publishToMatched(service, config, sender.names, body, mediaFiles)
    }

    /** Match the incoming address/display-name to phonebook entries. */
    private fun resolveSender(
        service: A8sService,
        config: A8sAndroid.Config,
        fromIdentity: String,
    ): Sender? {
        val direct = PhoneNormalize.matchPhonebookEntries(fromIdentity, config.phonebook)
        if (direct.isNotEmpty()) {
            return Sender(fromIdentity, direct.map { it.key }.toSet())
        }
        val resolved = phoneNumberForDisplayName(service, fromIdentity)
        if (resolved.isNullOrBlank()) {
            A8sAndroid.log("Ignored incoming from $fromIdentity (no phone number resolved)")
            return null
        }
        val byContact = PhoneNormalize.matchPhonebookEntries(resolved, config.phonebook)
        if (byContact.isEmpty()) {
            A8sAndroid.log(
                "Ignored incoming from $fromIdentity " +
                    "(resolved to ${PhoneNormalize.maskNumber(resolved)}, not in phonebook)",
            )
            return null
        }
        return Sender(resolved, byContact.map { it.key }.toSet())
    }

    /** @return true when the body was an SMS command (executed or rejected). */
    private fun handleSmsCommand(
        service: A8sService,
        config: A8sAndroid.Config,
        number: String,
        body: String,
    ): Boolean = when (val result = SmsSlashCommand.classify(number, body, config)) {
        is SmsSlashCommand.Result.Authorized -> {
            if (gateSmsOriginCommand(result.participantName, body)) {
                A8sAndroid.log("SMS command /${result.command.name} from ${PhoneNormalize.maskNumber(number)}")
                CommandDispatch.handle(result.command, service::executeCommand)
            } else {
                A8sAndroid.log("SMS command /${result.command.name} ignored (duplicate)")
            }
            true
        }
        is SmsSlashCommand.Result.Forbidden -> {
            A8sAndroid.log(
                "SMS command /${result.verb} from ${PhoneNormalize.maskNumber(number)} " +
                    "rejected (not permitted over SMS)",
            )
            service.sendSms(result.replyNumber, "'/${result.verb}' is not permitted over SMS.")
            true
        }
        SmsSlashCommand.Result.NotForSms -> false
    }

    private fun publishToMatched(
        service: A8sService,
        config: A8sAndroid.Config,
        names: Set<String>,
        body: String,
        mediaFiles: List<File>,
    ) {
        if (mediaFiles.isNotEmpty()) {
            Thread {
                val filesArr = service.buildFilesArray(config, mediaFiles)
                names.forEach { name -> publishOne(service, config, name, body, filesArr) }
                mediaFiles.forEach { it.delete() }
            }.start()
        } else {
            names.forEach { name -> publishOne(service, config, name, body, JSONArray()) }
        }
    }

    private fun publishOne(
        service: A8sService,
        config: A8sAndroid.Config,
        name: String,
        body: String,
        filesArr: JSONArray,
    ) {
        if (!service.publishDedup.shouldPublish("$name|$body")) {
            A8sAndroid.log("Skipping duplicate to $name (already sent recently)")
            return
        }
        val payload = buildIncomingPayload(config, name, body, filesArr)
        val (ok, fail) = service.publishToAllRemotes(config, payload)
        val fileNote = if (filesArr.length() > 0) "[+${filesArr.length()} file(s)] " else ""
        A8sAndroid.log(
            "SMS -> MQTT ${config.device} -> $name: ${service.preview(body)} $fileNote(${ok}/${ok + fail} remotes)",
        )
    }

    private fun buildIncomingPayload(
        config: A8sAndroid.Config,
        toName: String,
        body: String,
        filesArr: JSONArray,
    ): String = JSONObject().apply {
        put("id", Ulid.new())
        put("date", EnvelopeTime.isoNowUtc())
        put("from", config.device)
        put("to", toName)
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
