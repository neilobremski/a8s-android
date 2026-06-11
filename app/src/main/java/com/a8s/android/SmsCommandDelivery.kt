package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMS delivery for sub-identity forwards and SMS-originated command replies (#38).
 */
object SmsCommandDelivery {

    fun forwardToSms(service: A8sService, config: A8sAndroid.Config, forward: SubIdentityRoute.Forward) {
        if (!gateSubIdentityForward(forward.envelopeId, forward.smsToNumber, forward.content)) {
            A8sAndroid.log("Sub-identity forward to ${PhoneNormalize.maskNumber(forward.smsToNumber)} skipped (duplicate)")
            return
        }
        // Attribute the sender so the operator can tell agents apart.
        val attributed = if (forward.from.isNotBlank()) "${forward.from}: ${forward.content}" else forward.content
        A8sAndroid.log(
            "MQTT sub-identity -> SMS ${forward.from} -> ${PhoneNormalize.maskNumber(forward.smsToNumber)}: " +
                "${service.preview(forward.content)} [+${forward.files.size} file(s)]",
        )
        if (forward.files.isEmpty()) {
            service.sendSms(forward.smsToNumber, CmdHelpers.capForSms(attributed))
            return
        }
        Thread {
            val destDir = File(service.cacheDir, "subidentity-in")
            val results = FileDownloader.downloadFiles(forward.files, config.services, destDir)
            val body = FileDownloader.buildSmsBody(CmdHelpers.capForSms(attributed), results)
            service.sendSms(forward.smsToNumber, body)
            results.mapNotNull { it.file }.forEach { it.delete() }
        }.start()
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
