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

    private const val TELL_PREFS = "a8s_tell"
    private const val PINNED_AT_SUFFIX = ":pinnedAt"

    // Every pin read-then-write happens under this lock so an inbound
    // forward's conditional re-pin can never clobber a newer explicit
    // tell/fall-through write it did not see.
    private val pinLock = Any()

    fun setLastTellTarget(context: android.content.Context, senderAgent: String, targetAgent: String) {
        synchronized(pinLock) {
            val prefs = context.getSharedPreferences(TELL_PREFS, android.content.Context.MODE_PRIVATE)
            prefs.edit()
                .putString(senderAgent, targetAgent)
                .putLong(senderAgent + PINNED_AT_SUFFIX, System.currentTimeMillis())
                .apply()
        }
    }

    internal fun getLastTellTarget(context: android.content.Context, senderAgent: String): String? {
        synchronized(pinLock) {
            val prefs = context.getSharedPreferences(TELL_PREFS, android.content.Context.MODE_PRIVATE)
            return prefs.getString(senderAgent, null)
        }
    }

    /**
     * Bump the pin's timestamp only while it still points at
     * [expectedTarget] — a fall-through send refreshes the conversation it
     * actually joined, never one that superseded it mid-flight.
     */
    internal fun refreshPin(context: android.content.Context, senderAgent: String, expectedTarget: String) {
        synchronized(pinLock) {
            val prefs = context.getSharedPreferences(TELL_PREFS, android.content.Context.MODE_PRIVATE)
            if (prefs.getString(senderAgent, null) == expectedTarget) {
                prefs.edit().putLong(senderAgent + PINNED_AT_SUFFIX, System.currentTimeMillis()).apply()
            }
        }
    }

    /**
     * Atomic inbound re-pin: read the pin, decide, and mutate inside one
     * critical section. A legacy pin with no timestamp is stamped now and
     * kept — one full TTL window from first observation, then it ages
     * normally (see [StickyPin.Decision.STAMP_AND_KEEP]).
     */
    internal fun repinIfStale(
        context: android.content.Context,
        senderAgent: String,
        candidate: String,
        ttlMs: Long,
    ): StickyPin.Decision {
        synchronized(pinLock) {
            val prefs = context.getSharedPreferences(TELL_PREFS, android.content.Context.MODE_PRIVATE)
            val key = senderAgent + PINNED_AT_SUFFIX
            val target = prefs.getString(senderAgent, null)
            val pinnedAt = if (prefs.contains(key)) prefs.getLong(key, 0L) else null
            val now = System.currentTimeMillis()
            val decision = StickyPin.decide(target, pinnedAt, now, ttlMs)
            when (decision) {
                StickyPin.Decision.PIN_FIRST, StickyPin.Decision.REPIN ->
                    prefs.edit().putString(senderAgent, candidate).putLong(key, now).apply()
                StickyPin.Decision.STAMP_AND_KEEP ->
                    prefs.edit().putLong(key, now).apply()
                StickyPin.Decision.KEEP -> Unit
            }
            return decision
        }
    }

    private data class ResolvedSender(val number: String, val principal: Principal)

    data class IngressMeta(
        val source: IngressSource,
        val sourceEventId: String,
        val eventTimeMs: Long,
        val maxAgeMs: Long,
    )

    data class IncomingMessage(
        val fromIdentity: String,
        val body: String,
        val mediaFiles: List<File> = emptyList(),
        val replyAction: android.app.Notification.Action? = null,
        val ingress: IngressMeta,
    )

    internal data class PreparedIncoming(
        val agent: String,
        val number: String,
        val message: IncomingMessage,
    )

    internal fun mergePrepared(current: PreparedIncoming, incoming: PreparedIncoming): PreparedIncoming {
        val currentMessage = current.message
        val incomingMessage = incoming.message
        val body = when {
            currentMessage.body in GENERIC_MEDIA_BODIES -> incomingMessage.body
            incomingMessage.body in GENERIC_MEDIA_BODIES -> currentMessage.body
            else -> if (incomingMessage.mediaFiles.size > currentMessage.mediaFiles.size) {
                incomingMessage.body
            } else {
                currentMessage.body
            }
        }
        return current.copy(
            message = currentMessage.copy(
                body = body,
                mediaFiles = (currentMessage.mediaFiles + incomingMessage.mediaFiles).distinctBy { it.absolutePath },
                replyAction = incomingMessage.replyAction ?: currentMessage.replyAction,
            ),
        )
    }

    fun publishIncoming(service: A8sService, message: IncomingMessage) {
        val config = A8sAndroid.config ?: return
        val eventTimeMs = message.ingress.eventTimeMs
        val maxAgeMs = message.ingress.maxAgeMs
        if (IngressStaleness.isTooOld(eventTimeMs, maxAgeMs = maxAgeMs)) {
            A8sAndroid.log(
                "Ignored stale ingress from ${message.fromIdentity} " +
                    "(event ${ageMinutes(eventTimeMs)}m ago, max ${maxAgeMs / 60_000}m)",
            )
            message.mediaFiles.forEach { it.delete() }
            return
        }
        val sender = resolveSender(service, config, message.fromIdentity) ?: return
        service.submitPreparedIngress(PreparedIncoming(sender.principal.agent, sender.number, message))
    }

    internal fun processPrepared(service: A8sService, prepared: PreparedIncoming) {
        val config = A8sAndroid.config ?: return
        val sender = ResolvedSender(prepared.number, config.registry.principalByAgent(prepared.agent) ?: return)
        val message = prepared.message

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
            A8sAndroid.log(
                "SMS command /${result.command.name} from ${result.principal.agent} " +
                    "(${PhoneNormalize.maskNumber(sender.number)})",
            )
            CommandDispatch.handle(service, result.command, service::executeCommand)
            true
        }
        is SmsSlashCommand.Result.ConversationalTell -> handleConversationalTell(service, config, sender, result)
        is SmsSlashCommand.Result.Forbidden -> {
            A8sAndroid.log(
                "SMS command /${result.verb} from ${result.agent} " +
                    "(${PhoneNormalize.maskNumber(sender.number)}) rejected (not permitted)",
            )
            true
        }
        SmsSlashCommand.Result.NotForSms -> false
    }

    /**
     * Resolves a `hey`/`ok`/`okay` body's target before committing to it: an
     * unresolvable target means this was conversational text, not a command,
     * so the caller falls through to sticky routing instead of a usage error.
     */
    private fun handleConversationalTell(
        service: A8sService,
        config: A8sAndroid.Config,
        sender: ResolvedSender,
        result: SmsSlashCommand.Result.ConversationalTell,
    ): Boolean {
        val canonicalNames = config.registry.localAgents + config.device
        val resolution = NicknamesManager.resolveTell(service, result.command.args, canonicalNames)
        if (resolution == null || !NicknamesManager.isKnownTarget(resolution, canonicalNames)) return false
        A8sAndroid.log(
            "SMS conversational /${result.command.name} from ${result.principal.agent} " +
                "(${PhoneNormalize.maskNumber(sender.number)})",
        )
        CommandDispatch.handle(service, result.command, service::executeCommand)
        return true
    }

    private data class OutboundSms(val fromAgent: String, val toAgent: String, val body: String, val filesArr: JSONArray)

    private fun publishFallThrough(
        service: A8sService,
        config: A8sAndroid.Config,
        sender: ResolvedSender,
        body: String,
        mediaFiles: List<File>,
    ) {
        val toAgent = getLastTellTarget(service, sender.principal.agent)
        if (toAgent == null) {
            val msg = "No default agent set up. This message can't be delivered. " +
                "Use tell <agent> <message> (or hey/ok <agent> ...) to set your active agent."
            A8sAndroid.log("SMS fall-through from ${sender.principal.agent} rejected (no last /tell target)")
            service.sendSms(sender.number, msg)
            mediaFiles.forEach { it.delete() }
            return
        }
        refreshPin(service, sender.principal.agent, toAgent)
        if (mediaFiles.isNotEmpty()) {
            Thread {
                val filesArr = service.buildFilesArray(config, mediaFiles)
                val outbound = OutboundSms(sender.principal.agent, toAgent, body, filesArr)
                publishOne(service, config, outbound)
                AttachmentFailureAlert.build(filesArr)?.let { alert ->
                    service.sendSms(sender.number, alert)
                }
                mediaFiles.forEach { it.delete() }
            }.start()
        } else {
            val outbound = OutboundSms(sender.principal.agent, toAgent, body, JSONArray())
            publishOne(service, config, outbound)
        }
    }

    private fun publishOne(service: A8sService, config: A8sAndroid.Config, outbound: OutboundSms) {
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

    private val GENERIC_MEDIA_BODIES = setOf("[MMS media]", "Audio clip")
}
