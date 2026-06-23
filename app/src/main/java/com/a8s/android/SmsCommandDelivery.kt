package com.a8s.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** SMS delivery for phone-agent forwards and SMS-originated command replies. */
object SmsCommandDelivery {

    fun forwardToSms(service: A8sService, forward: PhoneAgentRoute.Forward) {
        if (!gatePhoneAgentForward(forward.envelopeId, forward.targetAgent, forward.content)) {
            A8sAndroid.log(
                "Phone-agent forward to ${forward.targetAgent} " +
                    "(${PhoneNormalize.maskNumber(forward.smsToNumber)}) skipped (duplicate)",
            )
            return
        }
        val attributed = if (forward.from.isNotBlank()) "${forward.from}: ${forward.content}" else forward.content
        A8sAndroid.log(
            "MQTT ${forward.targetAgent} -> SMS ${PhoneNormalize.maskNumber(forward.smsToNumber)}: " +
                "${service.preview(forward.content)} [+${forward.files.size} file(s)]",
        )
        val body = if (forward.files.isEmpty()) {
            CmdHelpers.capForSms(attributed)
        } else {
            CmdHelpers.buildSendBody(CmdHelpers.capForSms(attributed), forward.files)
        }
        service.sendSms(forward.smsToNumber, body)
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
