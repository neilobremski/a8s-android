package com.a8s.android

import android.hardware.display.DisplayManager
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.os.Build
import java.io.File

/**
 * `/macro <step1> | <step2> | …` — run a sequence of UI-automation
 * steps with full evidence: a `before` screenshot, a screen recording
 * of the steps as they run, an `after` screenshot, and a per-step
 * status summary in the reply text.
 *
 * Recording uses MediaProjection's VirtualDisplay piped into a
 * MediaRecorder, sharing the projection-consent token A8sService
 * already caches for `/screenshot`. No camera/mic involved — it's
 * pure screen capture (audio disabled because the macro is silent and
 * mic capture would compete with `/audio` paths if both ran).
 */
object CmdMacro {

    private const val VIDEO_WIDTH = 720
    private const val VIDEO_HEIGHT = 1280
    private const val VIDEO_BITRATE = 4_000_000
    private const val VIDEO_FRAMERATE = 30
    private const val PRE_RECORD_SETTLE_MS = 250L
    private const val POST_RECORD_SETTLE_MS = 500L

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val a11y = A11yService.instance
        if (a11y == null) {
            service.replyToSender(config, cmd.sender, A11Y_DISABLED_MSG)
            return
        }
        if (!service.hasProjectionConsent()) {
            service.replyToSender(
                config, cmd.sender,
                "Screen recording not authorized - tap Grant All Permissions.",
            )
            return
        }

        val raw = cmd.args.joinToString(" ").trim()
        if (raw.isEmpty()) {
            service.replyToSender(config, cmd.sender, "Usage: /macro <step1> | <step2> | …")
            return
        }
        val steps = MacroParser.parse(raw)
        if (steps.isEmpty()) {
            service.replyToSender(config, cmd.sender, "/macro: no steps parsed from input")
            return
        }

        val ulid = Ulid.new()
        val dir = File(service.cacheDir, "macro").apply { mkdirs() }
        val before = File(dir, "before-$ulid.png")
        val after = File(dir, "after-$ulid.png")
        val recording = File(dir, "recording-$ulid.mp4")

        if (!service.captureScreenshotPng(before)) {
            service.replyToSender(config, cmd.sender, "Macro failed: 'before' screenshot capture failed")
            return
        }

        val recorderState = startRecording(service, recording)
        if (recorderState == null) {
            service.replyToSender(config, cmd.sender, "Macro failed: screen recorder could not start")
            return
        }

        val started = System.currentTimeMillis()
        val statusLines = mutableListOf<String>()
        var aborted = false
        try {
            Thread.sleep(PRE_RECORD_SETTLE_MS)
            for ((i, step) in steps.withIndex()) {
                val stepNum = i + 1
                val (verb, ok, reason) = runStep(a11y, step)
                if (!ok) {
                    statusLines += "step $stepNum ($verb) failed: $reason"
                    aborted = true
                    break
                }
                statusLines += "step $stepNum ok ($verb)"
                Thread.sleep(POST_GESTURE_SETTLE_MS)
            }
            Thread.sleep(POST_RECORD_SETTLE_MS)
        } finally {
            stopRecording(recorderState)
        }

        val captured = service.captureScreenshotPng(after)
        val elapsedMs = System.currentTimeMillis() - started
        val header = if (aborted) "Macro aborted after ${statusLines.size} step(s) in ${elapsedMs}ms"
        else "Macro completed ${statusLines.size} step(s) in ${elapsedMs}ms"
        val body = (listOf(header) + statusLines).joinToString("\n")
        val files = mutableListOf(before)
        if (recording.exists() && recording.length() > 0) files += recording
        if (captured) files += after
        service.replyToSender(config, cmd.sender, body, files = files)
    }

    private data class StepResult(val verb: String, val ok: Boolean, val reason: String)

    @Suppress("ComplexMethod")
    private fun runStep(a11y: A11yService, step: MacroStep): StepResult = when (step) {
        is MacroStep.Tap -> {
            val ok = a11y.tap(step.x.toFloat(), step.y.toFloat())
            StepResult("tap", ok, if (ok) "" else "gesture rejected")
        }
        is MacroStep.LongTap -> {
            val ok = a11y.longTap(step.x.toFloat(), step.y.toFloat(), step.durationMs)
            StepResult("longtap", ok, if (ok) "" else "gesture rejected")
        }
        is MacroStep.Swipe -> {
            val ok = a11y.swipe(
                step.x1.toFloat(), step.y1.toFloat(),
                step.x2.toFloat(), step.y2.toFloat(),
                step.durationMs,
            )
            StepResult("swipe", ok, if (ok) "" else "gesture rejected")
        }
        is MacroStep.Key -> {
            val ok = a11y.globalAction(step.name)
            StepResult("key", ok, if (ok) "" else "unknown or rejected '${step.name}'")
        }
        is MacroStep.Input -> {
            val ok = a11y.inputText(step.text)
            StepResult("input", ok, if (ok) "" else "no field focused")
        }
        is MacroStep.Find -> {
            val ok = a11y.findAndTap(step.label)
            StepResult("find", ok, if (ok) "" else "no node matches '${step.label}'")
        }
        is MacroStep.Delay -> {
            Thread.sleep(step.ms)
            StepResult("delay", true, "")
        }
        is MacroStep.ParseError -> StepResult("parse", false, "${step.reason}: '${step.raw}'")
    }

    private data class RecorderState(
        val recorder: MediaRecorder,
        val projection: MediaProjection,
        val virtualDisplay: android.hardware.display.VirtualDisplay,
    )

    @Suppress("DEPRECATION")
    private fun startRecording(service: A8sService, dest: File): RecorderState? {
        dest.parentFile?.mkdirs()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(service)
        } else {
            MediaRecorder()
        }
        val projection = service.acquireMediaProjection() ?: run {
            try { recorder.release() } catch (_: Exception) { }
            return null
        }
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setOutputFile(dest.absolutePath)
            recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoEncodingBitRate(VIDEO_BITRATE)
            recorder.setVideoFrameRate(VIDEO_FRAMERATE)
            recorder.prepare()
        } catch (e: Exception) {
            A8sAndroid.log("Macro recorder prepare failed: ${e.message}")
            try { recorder.release() } catch (_: Exception) { }
            try { projection.stop() } catch (_: Exception) { }
            return null
        }
        val virtualDisplay = try {
            projection.createVirtualDisplay(
                "a8s-macro",
                VIDEO_WIDTH, VIDEO_HEIGHT,
                service.resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                recorder.surface,
                null, null,
            )
        } catch (e: Exception) {
            A8sAndroid.log("Macro virtualDisplay create failed: ${e.message}")
            try { recorder.release() } catch (_: Exception) { }
            try { projection.stop() } catch (_: Exception) { }
            return null
        }
        try {
            recorder.start()
        } catch (e: Exception) {
            A8sAndroid.log("Macro recorder start failed: ${e.message}")
            try { virtualDisplay.release() } catch (_: Exception) { }
            try { recorder.release() } catch (_: Exception) { }
            try { projection.stop() } catch (_: Exception) { }
            return null
        }
        return RecorderState(recorder, projection, virtualDisplay)
    }

    private fun stopRecording(state: RecorderState) {
        try { state.recorder.stop() } catch (e: Exception) {
            A8sAndroid.log("Macro recorder stop: ${e.message}")
        }
        try { state.recorder.reset() } catch (_: Exception) { }
        try { state.recorder.release() } catch (_: Exception) { }
        try { state.virtualDisplay.release() } catch (_: Exception) { }
        try { state.projection.stop() } catch (_: Exception) { }
    }
}
