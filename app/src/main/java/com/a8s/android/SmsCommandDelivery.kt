package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SMS delivery for phone-agent forwards and SMS-originated command replies. */
object SmsCommandDelivery {

    fun forwardToSms(service: A8sService, forward: PhoneAgentRoute.Forward, config: A8sAndroid.Config) {
        val txnId = forward.envelopeId
        val maskedTo = TransactionTrace.maskTo(forward.smsToNumber)
        if (!gatePhoneAgentForward(service, forward.envelopeId, forward.targetAgent, forward.content)) {
            A8sAndroid.log(
                "Phone-agent forward to ${forward.targetAgent} " +
                    "(${PhoneNormalize.maskNumber(forward.smsToNumber)}) skipped (duplicate)",
            )
            TransactionTrace.record(
                TransactionTrace.Event(
                    txnId = txnId,
                    flow = "PHONE_FWD",
                    status = TransactionTrace.Status.SKIP,
                    from = forward.from,
                    to = maskedTo,
                    summary = "duplicate envelope (dedup gate)",
                ),
            )
            return
        }
        A8sAndroid.log(
            "MQTT ${forward.targetAgent} -> SMS ${PhoneNormalize.maskNumber(forward.smsToNumber)}: " +
                "${service.preview(forward.content)} [+${forward.files.size} file(s)]",
        )
        maybeRepinStickyTarget(service, config, forward)

        Thread {
            handleForwardToSmsThread(service, forward, config)
        }.start()
    }

    /**
     * An inbound agent forward re-pins the sticky SMS target to itself only
     * when the phone principal's current pin is absent or stale — a fresh
     * pin (set by an explicit `tell`/`hey`/`ok`, or refreshed by a recent
     * plain-text fall-through) is never stolen mid-conversation. The read,
     * decision, and write are one atomic operation under the pin lock.
     */
    private fun maybeRepinStickyTarget(service: A8sService, config: A8sAndroid.Config, forward: PhoneAgentRoute.Forward) {
        val outcome = IncomingSmsRouter.repinIfStale(
            service,
            forward.targetAgent,
            forward.from,
            config.smsStickyTtlMs,
        )
        if (outcome == StickyPin.Decision.REPIN) {
            A8sAndroid.log("SMS sticky target now ${forward.from} (previous pin stale)")
        }
    }

    private fun handleForwardToSmsThread(
        service: A8sService,
        forward: PhoneAgentRoute.Forward,
        config: A8sAndroid.Config,
    ) {
        val txnId = forward.envelopeId
        val maskedTo = TransactionTrace.maskTo(forward.smsToNumber)
        val attributed = if (forward.from.isNotBlank()) "${forward.from}: ${forward.content}" else forward.content
        val allEnvelopeFiles = forward.files
        val fileDetail = summarizeForwardFiles(allEnvelopeFiles)
        val finalBody = CmdHelpers.buildSendBody(attributed, allEnvelopeFiles, config.smsRawStorageRefs)
        service.sendSms(forward.smsToNumber, finalBody)

        if (allEnvelopeFiles.isEmpty()) {
            TransactionTrace.record(
                TransactionTrace.Event(
                    txnId = txnId,
                    flow = "PHONE_FWD",
                    status = TransactionTrace.Status.OK,
                    from = forward.from,
                    to = maskedTo,
                    summary = "text: ${service.preview(forward.content)}",
                    detail = "files: none\nsms: queued as one logical message",
                ),
            )
            return
        }
        
        val withUrls = allEnvelopeFiles.count { it.storageUrls.isNotEmpty() }
        val status = when {
            withUrls == allEnvelopeFiles.size -> TransactionTrace.Status.OK
            withUrls > 0 -> TransactionTrace.Status.PARTIAL
            else -> TransactionTrace.Status.FAIL
        }
        val smsNote = when {
            withUrls == 0 -> "sms: queued as one logical message — no storage url(s) in envelope"
            withUrls < allEnvelopeFiles.size -> 
                "sms: queued with inline URL(s) for $withUrls/${allEnvelopeFiles.size} file(s)"
            else -> "sms: queued with inline URL(s) for all file(s)"
        }
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = txnId,
                flow = "PHONE_FWD",
                status = status,
                from = forward.from,
                to = maskedTo,
                summary = "text: ${service.preview(forward.content)}",
                detail = fileDetail + "\n$smsNote",
            ),
        )
    }

    private fun summarizeForwardFiles(files: List<EnvelopeFile>): String =
        files.joinToString("\n") { ef ->
            val n = ef.storageUrls.size
            when {
                n == 0 -> "  • ${ef.filename}: NO storage urls in envelope"
                else -> "  • ${ef.filename}: $n storage url(s) → ${ef.storageUrls.first()}"
            }
        }

    fun smsBodyWithUploads(
        service: A8sService,
        config: A8sAndroid.Config,
        text: String,
        files: List<File>,
        existingEnvelopeFiles: List<EnvelopeFile> = emptyList(),
    ): String {
        val uploadedEnvelopeFiles = if (files.isNotEmpty()) {
            val arr = service.buildFilesArrayForSms(config, files)
            envelopeFilesFromJSONArray(arr)
        } else {
            emptyList()
        }
        val allEnvelopeFiles = existingEnvelopeFiles + uploadedEnvelopeFiles
        return CmdHelpers.buildSendBody(text, allEnvelopeFiles, config.smsRawStorageRefs)
    }

    private fun envelopeFilesFromJSONArray(arr: JSONArray): List<EnvelopeFile> {
        val out = mutableListOf<EnvelopeFile>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val filename = obj.optString("filename")
            val storage = obj.optJSONArray("storage") ?: JSONArray()
            val urls = mutableListOf<String>()
            for (j in 0 until storage.length()) {
                val url = storage.optString(j)
                if (url.isNotBlank()) urls += url
            }
            if (filename.isNotBlank()) {
                out += EnvelopeFile(
                    filename,
                    urls,
                    error = obj.optString("error").ifBlank { null },
                    detail = obj.optString("detail"),
                )
            }
        }
        return out
    }
}
