package com.a8s.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.File

/**
 * `/audio [seconds]` — record an audio-only snippet via MediaRecorder
 * MIC source, save to `cacheDir/audio/audio-<ts>.m4a`, reply with the
 * file attached. Default 10s, hard-capped by `CmdHelpers.parseAudioSeconds`.
 *
 * Same threading shape as /video (worker thread blocks on `Thread.sleep`
 * for the requested duration, then stops the recorder). No camera, no
 * VirtualDisplay — just the mic.
 */
object CmdAudio {

    private const val AUDIO_BITRATE = 128_000
    private const val AUDIO_SAMPLE_RATE = 44_100

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val seconds = CmdHelpers.parseAudioSeconds(cmd.args)
        val context: Context = service
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            service.replyToSender(
                config, cmd.sender,
                "Audio failed: RECORD_AUDIO permission not granted. " +
                    "Open the app and tap \"Grant All Permissions\".",
            )
            return
        }
        val dest = File(File(context.cacheDir, "audio"), "audio-${System.currentTimeMillis()}.m4a")
        dest.parentFile?.mkdirs()
        val recorder = buildRecorder(context, dest)
        try {
            recorder.prepare()
        } catch (e: Exception) {
            A8sAndroid.log("MediaRecorder prepare failed: ${e.message}")
            recorder.release()
            service.replyToSender(config, cmd.sender, "Audio failed: prepare: ${e.message}")
            return
        }
        try {
            recorder.start()
            try {
                Thread.sleep(seconds * 1000L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            try { recorder.stop() } catch (e: Exception) {
                A8sAndroid.log("MediaRecorder.stop: ${e.message}")
            }
        } finally {
            try { recorder.release() } catch (_: Exception) { }
        }
        if (dest.length() == 0L) {
            service.replyToSender(config, cmd.sender, "Audio failed: empty output")
            return
        }
        A8sAndroid.log("Audio captured: ${dest.length()} bytes (${seconds}s)")
        service.replyToSender(
            config, cmd.sender,
            "Audio (${CmdHelpers.humanSize(dest.length())}, ${seconds}s)",
            files = listOf(dest),
        )
    }

    @Suppress("DEPRECATION")
    private fun buildRecorder(context: Context, dest: File): MediaRecorder {
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(AUDIO_BITRATE)
        recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
        recorder.setOutputFile(dest.absolutePath)
        return recorder
    }
}
