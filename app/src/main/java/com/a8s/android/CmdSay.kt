package com.a8s.android

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `/say <text>` — speak text aloud through the phone speaker via
 * Android's built-in TextToSpeech. Blocks the worker thread until
 * playback finishes or a generous timeout fires, so the reply
 * (`Spoke: <text>`) accurately reflects completion.
 */
object CmdSay {

    private const val INIT_TIMEOUT_S = 5L
    private const val SPEAK_TIMEOUT_S = 60L

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val text = CmdHelpers.parseSayText(cmd.args)
        if (text == null) {
            service.replyToOwner(config, cmd.owner, "Usage: /say <text>")
            return
        }
        val context: Context = service
        val initLatch = CountDownLatch(1)
        val initOk = arrayOf(false)
        var tts: TextToSpeech? = null
        try {
            tts = TextToSpeech(context) { status ->
                initOk[0] = status == TextToSpeech.SUCCESS
                initLatch.countDown()
            }
            if (!initLatch.await(INIT_TIMEOUT_S, TimeUnit.SECONDS) || !initOk[0]) {
                service.replyToOwner(config, cmd.owner, "Speak failed: TTS engine init failed")
                return
            }
            tts.language = Locale.US

            val doneLatch = CountDownLatch(1)
            val errorMsg = arrayOf<String?>(null)
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}
                override fun onDone(id: String?) { doneLatch.countDown() }
                @Deprecated("Deprecated in API")
                override fun onError(id: String?) {
                    errorMsg[0] = "TTS error"
                    doneLatch.countDown()
                }
                override fun onError(id: String?, errorCode: Int) {
                    errorMsg[0] = "TTS error (code $errorCode)"
                    doneLatch.countDown()
                }
            })
            val utt = "a8s-say-${System.currentTimeMillis()}"
            val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utt)
            if (rc != TextToSpeech.SUCCESS) {
                service.replyToOwner(config, cmd.owner, "Speak failed: speak() rc=$rc")
                return
            }
            if (!doneLatch.await(SPEAK_TIMEOUT_S, TimeUnit.SECONDS)) {
                service.replyToOwner(config, cmd.owner, "Speak failed: timeout")
                return
            }
            val err = errorMsg[0]
            if (err != null) {
                service.replyToOwner(config, cmd.owner, "Speak failed: $err")
                return
            }
            service.replyToOwner(config, cmd.owner, "Spoke: $text")
        } catch (e: Exception) {
            A8sAndroid.log("Say failed: ${e.message}")
            service.replyToOwner(config, cmd.owner, "Speak failed: ${e.message}")
        } finally {
            try { tts?.stop() } catch (_: Exception) { }
            try { tts?.shutdown() } catch (_: Exception) { }
        }
    }
}
