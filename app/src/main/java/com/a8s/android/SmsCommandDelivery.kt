package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMS delivery for sub-identity forwards and SMS-originated command replies (#38).
 */
object SmsCommandDelivery {

    fun forwardToSms(service: A8sService, forward: SubIdentityRoute.Forward) {
        val txnId = forward.envelopeId
        val maskedTo = TransactionTrace.maskTo(forward.smsToNumber)
        if (!gateSubIdentityForward(forward.envelopeId, forward.smsToNumber, forward.content)) {
            A8sAndroid.log("Sub-identity forward to ${PhoneNormalize.maskNumber(forward.smsToNumber)} skipped (duplicate)")
            TransactionTrace.record(
                TransactionTrace.Event(
                    txnId = txnId,
                    flow = "SUB_FWD",
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
            "MQTT sub-identity -> SMS ${forward.from} -> ${PhoneNormalize.maskNumber(forward.smsToNumber)}: " +
                "${service.preview(forward.content)} [+${forward.files.size} file(s)]",
        )
        val fileDetail = summarizeForwardFiles(forward.files)
        if (forward.files.isEmpty()) {
            service.sendSms(forward.smsToNumber, CmdHelpers.capForSms(attributed))
            TransactionTrace.record(
                TransactionTrace.Event(
                    txnId = txnId,
                    flow = "SUB_FWD",
                    status = TransactionTrace.Status.OK,
                    from = forward.from,
                    to = maskedTo,
                    summary = "text: ${service.preview(forward.content)}",
                    detail = "files: none\nsms: sent text-only",
                ),
            )
            return
        }
        // SMS cannot carry binary attachments — append storage URLs inline
        // (same strategy as `/send`). Download-first was deleting files without
        // ever putting URLs in the body when retrieval succeeded.
        val body = CmdHelpers.buildSendBody(CmdHelpers.capForSms(attributed), forward.files)
        service.sendSms(forward.smsToNumber, body)
        val withUrls = forward.files.count { it.storageUrls.isNotEmpty() }
        val status = when {
            withUrls == forward.files.size -> TransactionTrace.Status.OK
            withUrls > 0 -> TransactionTrace.Status.PARTIAL
            else -> TransactionTrace.Status.FAIL
        }
        val smsNote = when {
            withUrls == 0 -> "sms: sent text-only — no storage url(s) in envelope"
            withUrls < forward.files.size -> "sms: sent with inline URL(s) for $withUrls/${forward.files.size} file(s)"
            else -> "sms: sent with inline URL(s) for all file(s)"
        }
        TransactionTrace.record(
            TransactionTrace.Event(
                txnId = txnId,
                flow = "SUB_FWD",
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
        if (files.isEmpty()) return capped
        val arr = service.buildFilesArrayForSms(config, files)
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
