package com.a8s.android

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
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

    // Some Android devices' TTS engines take longer than 5s to come up
    // cold (e.g. when the system is downloading voice data on first
    // request). 15s is generous; we still bail early if the engine
    // returns an error synchronously.
    private const val INIT_TIMEOUT_S = 15L
    private const val SPEAK_TIMEOUT_S = 60L

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val text = CmdHelpers.parseSayText(cmd.args)
        if (text == null) {
            service.replyToOwner(config, cmd.owner, "Usage: /say <text>")
            return
        }
        val context: Context = service
        var tts: TextToSpeech? = null
        try {
            tts = initTts(context) ?: run {
                service.replyToOwner(config, cmd.owner, ttsInitErrorMessage(null))
                return
            }
            val initErr = checkTtsReady(tts)
            if (initErr != null) {
                service.replyToOwner(config, cmd.owner, initErr)
                return
            }
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
            // Force STREAM_MUSIC at full volume. TTS engines default to
            // varying streams across vendors; if media volume is at zero
            // the user hears nothing and we'd silently report success.
            // Setting the param explicitly + reporting the volume in the
            // reply lets the operator diagnose "I didn't hear anything"
            // immediately.
            val params = Bundle().apply {
                putString(
                    TextToSpeech.Engine.KEY_PARAM_STREAM,
                    AudioManager.STREAM_MUSIC.toString(),
                )
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
            }
            val rc = tts.speak(text, TextToSpeech.QUEUE_FLUSH, params, utt)
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
            val volPct = mediaVolumePercent(context)
            val volNote = when {
                volPct == 0 -> " (media volume is 0% — turn it up to hear /say)"
                volPct < 25 -> " (media volume is $volPct%)"
                else -> ""
            }
            service.replyToOwner(config, cmd.owner, "Spoke: $text$volNote")
        } catch (e: Exception) {
            A8sAndroid.log("Say failed: ${e.message}")
            service.replyToOwner(config, cmd.owner, "Speak failed: ${e.message}")
        } finally {
            try { tts?.stop() } catch (_: Exception) { }
            try { tts?.shutdown() } catch (_: Exception) { }
        }
    }

    private fun mediaVolumePercent(context: Context): Int {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return -1
        val vol = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return if (max > 0) (vol * 100) / max else -1
    }

    /** Construct a TextToSpeech engine and block until init fires (or
     *  the timeout elapses). Returns the TTS instance on init success;
     *  null if the engine timed out or returned ERROR. Caller surfaces
     *  the error message via `ttsInitErrorMessage`. */
    private fun initTts(context: Context): TextToSpeech? {
        val initLatch = CountDownLatch(1)
        val initStatus = arrayOf<Int?>(null)
        val tts = TextToSpeech(context) { status ->
            initStatus[0] = status
            initLatch.countDown()
        }
        if (!initLatch.await(INIT_TIMEOUT_S, TimeUnit.SECONDS)) {
            try { tts.shutdown() } catch (_: Exception) { }
            return null
        }
        return if (initStatus[0] == TextToSpeech.SUCCESS) tts else null
    }

    private fun ttsInitErrorMessage(tts: TextToSpeech?): String {
        val engines = try {
            tts?.engines?.joinToString(", ") { it.name }.orEmpty()
        } catch (_: Exception) { "" }
        val enginesPart = if (engines.isBlank()) "(none installed)" else engines
        return "Speak failed: TTS engine did not initialize. Engines available: " +
            "$enginesPart. Install Google TTS (com.google.android.tts) from the Play " +
            "Store and set it as the default TTS engine in Settings → Text-to-speech."
    }

    /** Set en-US and check the result. Returns null on success, an
     *  error message on language-data missing / not supported. */
    private fun checkTtsReady(tts: TextToSpeech): String? {
        val rc = tts.setLanguage(Locale.US)
        return if (rc == TextToSpeech.LANG_MISSING_DATA || rc == TextToSpeech.LANG_NOT_SUPPORTED) {
            "Speak failed: en-US not supported by the selected TTS engine (rc=$rc). " +
                "Install Google TTS or download the en-US voice in Settings → " +
                "Text-to-speech → Install voice data."
        } else {
            null
        }
    }
}
