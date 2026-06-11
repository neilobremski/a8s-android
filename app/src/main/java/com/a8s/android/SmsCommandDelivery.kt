package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * SMS delivery for sub-identity forwards and SMS-originated command replies (#38).
 */
object SmsCommandDelivery {

    fun forwardToSms(service: A8sService, config: A8sAndroid.Config, forward: SubIdentityRoute.Forward) {
        val preview = service.preview(forward.content)
        A8sAndroid.log(
            "MQTT sub-identity -> SMS ${forward.from} -> ${forward.smsToNumber}: $preview " +
                "[+${forward.files.size} file(s)]",
        )
        if (forward.files.isEmpty()) {
            service.sendSms(forward.smsToNumber, forward.content)
            return
        }
        Thread {
            val destDir = File(service.cacheDir, "subidentity-in")
            val results = FileDownloader.downloadFiles(forward.files, config.services, destDir)
            val body = FileDownloader.buildSmsBody(forward.content, results)
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
        if (files.isEmpty()) return text
        val arr = service.buildFilesArrayForSms(config, files)
        val envelopeFiles = envelopeFilesFromJSONArray(arr)
        return CmdHelpers.buildSendBody(text, envelopeFiles)
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
