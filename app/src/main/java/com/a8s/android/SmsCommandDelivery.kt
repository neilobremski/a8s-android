package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SMS delivery for phone-agent forwards and SMS-originated command replies. */
object SmsCommandDelivery {

    fun forwardToSms(service: A8sService, forward: PhoneAgentRoute.Forward) {
        val txnId = forward.envelopeId
        val maskedTo = TransactionTrace.maskTo(forward.smsToNumber)
        if (!gatePhoneAgentForward(forward.envelopeId, forward.targetAgent, forward.content)) {
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
        val attributed = if (forward.from.isNotBlank()) "${forward.from}: ${forward.content}" else forward.content
        A8sAndroid.log(
            "MQTT ${forward.targetAgent} -> SMS ${PhoneNormalize.maskNumber(forward.smsToNumber)}: " +
                "${service.preview(forward.content)} [+${forward.files.size} file(s)]",
        )

        Thread {
            handleForwardToSmsThread(service, forward, txnId, maskedTo, attributed)
        }.start()
    }

    private fun handleForwardToSmsThread(
        service: A8sService,
        forward: PhoneAgentRoute.Forward,
        txnId: String,
        maskedTo: String,
        attributed: String,
    ) {
        var extraFiles = emptyList<EnvelopeFile>()
        if (attributed.length > CmdHelpers.MAX_SMS_REPLY_CHARS) {
            try {
                val tempFile = File.createTempFile("full-message-", ".txt", service.cacheDir)
                tempFile.writeText(attributed)
                val config = A8sAndroid.config
                if (config != null) {
                    val arr = service.buildFilesArrayForSms(config, listOf(tempFile))
                    extraFiles = envelopeFilesFromJSONArray(arr)
                }
            } catch (e: Exception) {
                A8sAndroid.log("Failed to upload truncated text: ${e.message}")
            }
        }

        val allEnvelopeFiles = forward.files + extraFiles
        val fileDetail = summarizeForwardFiles(allEnvelopeFiles)
        if (allEnvelopeFiles.isEmpty()) {
            service.sendSms(forward.smsToNumber, CmdHelpers.capForSms(attributed))
            TransactionTrace.record(
                TransactionTrace.Event(
                    txnId = txnId,
                    flow = "PHONE_FWD",
                    status = TransactionTrace.Status.OK,
                    from = forward.from,
                    to = maskedTo,
                    summary = "text: ${service.preview(forward.content)}",
                    detail = "files: none\nsms: sent text-only",
                ),
            )
            return
        }
        val body = CmdHelpers.buildSendBody(CmdHelpers.capForSms(attributed), allEnvelopeFiles)
        service.sendSms(forward.smsToNumber, body)
        val withUrls = allEnvelopeFiles.count { it.storageUrls.isNotEmpty() }
        val status = when {
            withUrls == allEnvelopeFiles.size -> TransactionTrace.Status.OK
            withUrls > 0 -> TransactionTrace.Status.PARTIAL
            else -> TransactionTrace.Status.FAIL
        }
        val smsNote = when {
            withUrls == 0 -> "sms: sent text-only — no storage url(s) in envelope"
            withUrls < allEnvelopeFiles.size -> "sms: sent with inline URL(s) for $withUrls/${allEnvelopeFiles.size} file(s)"
            else -> "sms: sent with inline URL(s) for all file(s)"
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
    ): String {
        val capped = CmdHelpers.capForSms(text)
        var allFiles = files
        if (text.length > CmdHelpers.MAX_SMS_REPLY_CHARS) {
            try {
                val tempFile = File.createTempFile("full-message-", ".txt", service.cacheDir)
                tempFile.writeText(text)
                allFiles = allFiles + tempFile
            } catch (e: Exception) {
                A8sAndroid.log("Failed to write truncated text to file: ${e.message}")
            }
        }
        if (allFiles.isEmpty()) return capped
        val arr = service.buildFilesArrayForSms(config, allFiles)
        val envelopeFiles = envelopeFilesFromJSONArray(arr)
        return CmdHelpers.buildSendBody(capped, envelopeFiles)
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
                out += EnvelopeFile(filename, urls)
            }
        }
        return out
    }
}
