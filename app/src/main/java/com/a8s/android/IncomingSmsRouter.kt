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

    fun publishIncoming(
        service: A8sService,
        fromIdentity: String,
        body: String,
        mediaFiles: List<File> = emptyList(),
        replyAction: android.app.Notification.Action? = null,
    ) {
        val config = A8sAndroid.config ?: return

        val direct = fromIdentity.replace("[^0-9+]".toRegex(), "")
        val phonebookNames = if (direct.isNotEmpty()) {
            config.phonebook.filterValues { it.replace("[^0-9+]".toRegex(), "") == direct }.keys
        } else {
            emptySet()
        }
        val resolvedNumber: String
        val matchedNames = if (phonebookNames.isNotEmpty()) {
            resolvedNumber = direct
            phonebookNames
        } else {
            val resolved = phoneNumberForDisplayName(service, fromIdentity)
                ?.replace("[^0-9+]".toRegex(), "")
            if (resolved.isNullOrEmpty()) {
                A8sAndroid.log("Ignored incoming from $fromIdentity (no phone number resolved)")
                return
            }
            resolvedNumber = resolved
            val byContact = config.phonebook.filterValues {
                it.replace("[^0-9+]".toRegex(), "") == resolved
            }.keys
            if (byContact.isEmpty()) {
                A8sAndroid.log("Ignored incoming from $fromIdentity (resolved to $resolved, not in phonebook)")
                return
            }
            byContact
        }

        if (replyAction != null && resolvedNumber.isNotEmpty()) {
            A8sAndroid.cacheReplyAction(resolvedNumber, replyAction)
        }

        SmsSlashCommand.tryParse(resolvedNumber, body, config)?.let { authorized ->
            A8sAndroid.log(
                "SMS command /${authorized.command.name} from ${authorized.replyNumber}",
            )
            CommandDispatch.handle(authorized.command, service::executeCommand)
            return
        }

        if (mediaFiles.isNotEmpty()) {
            Thread {
                val filesArr = service.buildFilesArray(config, mediaFiles)
                matchedNames.forEach { name ->
                    if (!service.publishDedup.shouldPublish("$name|$body")) {
                        A8sAndroid.log("Skipping duplicate to $name (already sent recently)")
                        return@forEach
                    }
                    val payload = buildIncomingPayload(config, name, body, filesArr)
                    val (ok, fail) = service.publishToAllRemotes(config, payload)
                    A8sAndroid.log(
                        "SMS -> MQTT ${config.device} -> $name: ${service.preview(body)} " +
                            "[+${mediaFiles.size} file(s)] (${ok}/${ok + fail} remotes)",
                    )
                }
                mediaFiles.forEach { it.delete() }
            }.start()
        } else {
            matchedNames.forEach { name ->
                if (!service.publishDedup.shouldPublish("$name|$body")) {
                    A8sAndroid.log("Skipping duplicate to $name (already sent recently)")
                    return@forEach
                }
                val payload = buildIncomingPayload(config, name, body, JSONArray())
                val (ok, fail) = service.publishToAllRemotes(config, payload)
                A8sAndroid.log(
                    "SMS -> MQTT ${config.device} -> $name: ${service.preview(body)} " +
                        "(${ok}/${ok + fail} remotes)",
                )
            }
        }
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
