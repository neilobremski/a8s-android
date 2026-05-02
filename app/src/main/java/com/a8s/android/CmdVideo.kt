package com.a8s.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `/video [seconds]` — record a fixed-length MP4 via Camera2 +
 * MediaRecorder, save to `cacheDir/videos/video-<ts>.mp4`, reply with
 * the file attached. Default 10s, hard-capped at 30s by
 * `CmdHelpers.parseVideoSeconds`.
 *
 * The recorder is wired to a Camera2 capture session whose target is
 * the recorder's input surface. Threading: a per-call HandlerThread
 * dispatches Camera2 callbacks; the worker thread blocks on
 * CountDownLatches, sleeps for the requested duration, then stops the
 * recorder and tears everything down.
 */
object CmdVideo {

    private const val OPEN_TIMEOUT_S = 15L
    private const val SESSION_TIMEOUT_S = 15L
    private const val VIDEO_WIDTH = 1280
    private const val VIDEO_HEIGHT = 720
    private const val VIDEO_BITRATE = 4_000_000
    private const val VIDEO_FRAMERATE = 30
    private const val AUDIO_BITRATE = 128_000
    private const val AUDIO_SAMPLE_RATE = 44_100

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val seconds = CmdHelpers.parseVideoSeconds(cmd.args)
        val context: Context = service
        // Pre-check RECORD_AUDIO. Without it, MediaRecorder happily produces
        // a silent video — much worse than a clear error message that
        // points at the missing permission.
        if (androidx.core.content.ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO,
            ) != PackageManager.PERMISSION_GRANTED) {
            service.replyToOwner(
                config, cmd.owner,
                "Video failed: RECORD_AUDIO permission not granted. " +
                    "Open the app and tap \"Grant All Permissions\".",
            )
            return
        }
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (mgr == null) {
            service.replyToOwner(config, cmd.owner, "Video failed: camera service unavailable")
            return
        }
        val cameraId = findBackCameraId(mgr)
        if (cameraId == null) {
            service.replyToOwner(config, cmd.owner, "Video failed: no back camera found")
            return
        }

        val dest = File(File(context.cacheDir, "videos"), "video-${System.currentTimeMillis()}.mp4")
        dest.parentFile?.mkdirs()
        val thread = HandlerThread("a8s-video").apply { start() }
        val handler = Handler(thread.looper)
        val params = RecordParams(context, mgr, cameraId, handler, dest, seconds)

        try {
            val ok = record(params)
            if (!ok || dest.length() == 0L) {
                service.replyToOwner(config, cmd.owner, "Video failed: nothing recorded")
                return
            }
            A8sAndroid.log("Video recorded: ${dest.length()} bytes (${seconds}s)")
            service.replyToOwner(
                config, cmd.owner,
                "Video (${CmdHelpers.humanSize(dest.length())}, ${seconds}s)",
                files = listOf(dest),
            )
        } catch (e: Exception) {
            A8sAndroid.log("Video failed: ${e.message}")
            service.replyToOwner(config, cmd.owner, "Video failed: ${e.message}")
        } finally {
            thread.quitSafely()
        }
    }

    private fun findBackCameraId(mgr: CameraManager): String? = try {
        mgr.cameraIdList.firstOrNull { id ->
            mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
    } catch (_: CameraAccessException) {
        null
    }

    private data class RecordParams(
        val context: Context,
        val mgr: CameraManager,
        val cameraId: String,
        val handler: Handler,
        val dest: File,
        val seconds: Int,
    )

    @Suppress("LongMethod", "ReturnCount", "ComplexMethod")
    private fun record(p: RecordParams): Boolean {
        val context = p.context
        val mgr = p.mgr
        val cameraId = p.cameraId
        val handler = p.handler
        val dest = p.dest
        val seconds = p.seconds
        val orientation = videoOrientation(context, mgr, cameraId)
        val recorder = buildRecorder(context, dest, orientation)
        try {
            recorder.prepare()
        } catch (e: Exception) {
            A8sAndroid.log("MediaRecorder prepare failed: ${e.message}")
            recorder.release()
            return false
        }
        val recordSurface = recorder.surface

        var device: CameraDevice? = null
        val openLatch = CountDownLatch(1)
        var openError: String? = null
        try {
            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    openLatch.countDown()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    openError = "camera disconnected"; camera.close(); openLatch.countDown()
                }
                override fun onError(camera: CameraDevice, errCode: Int) {
                    openError = "camera error: $errCode"; camera.close(); openLatch.countDown()
                }
            }, handler)
        } catch (e: SecurityException) {
            A8sAndroid.log("Camera permission denied: ${e.message}")
            recorder.release()
            return false
        }
        if (!openLatch.await(OPEN_TIMEOUT_S, TimeUnit.SECONDS) || device == null) {
            A8sAndroid.log("Video: open camera failed (${openError ?: "timeout"})")
            recorder.release()
            return false
        }

        val d = device!!
        val sessionLatch = CountDownLatch(1)
        var session: CameraCaptureSession? = null
        var sessionError: String? = null
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf<Surface>(recordSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        sessionError = "session configure failed"
                        sessionLatch.countDown()
                    }
                }, handler)
        } catch (e: CameraAccessException) {
            A8sAndroid.log("Video session create failed: ${e.message}")
            d.close(); recorder.release()
            return false
        }
        if (!sessionLatch.await(SESSION_TIMEOUT_S, TimeUnit.SECONDS) || session == null) {
            A8sAndroid.log("Video: session not ready (${sessionError ?: "timeout"})")
            try { d.close() } catch (_: Exception) { }
            recorder.release()
            return false
        }

        val s = session!!
        try {
            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(recordSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            }.build()
            s.setRepeatingRequest(req, null, handler)
            recorder.start()
            Thread.sleep(seconds * 1000L)
            try { recorder.stop() } catch (e: Exception) {
                A8sAndroid.log("MediaRecorder.stop: ${e.message}")
            }
            return true
        } catch (e: Exception) {
            A8sAndroid.log("Video record loop: ${e.message}")
            return false
        } finally {
            try { s.close() } catch (_: Exception) { }
            try { d.close() } catch (_: Exception) { }
            try { recorder.reset() } catch (_: Exception) { }
            recorder.release()
        }
    }

    @Suppress("DEPRECATION")
    private fun buildRecorder(context: Context, dest: File, orientationHint: Int): MediaRecorder {
        // MediaRecorder() default constructor was deprecated in API 31 in
        // favor of the Context-taking overload; using the new one when
        // available keeps lint quiet without forcing a min-SDK bump.
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            MediaRecorder()
        }
        // MIC is more universally supported than CAMCORDER — some devices
        // route CAMCORDER through the back-mic only and produce a silent
        // track when the front camera is in use, or fail to acquire any
        // input at all. MIC is the default mic stream and reliable.
        recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
        recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        recorder.setOutputFile(dest.absolutePath)
        recorder.setVideoEncodingBitRate(VIDEO_BITRATE)
        recorder.setVideoFrameRate(VIDEO_FRAMERATE)
        recorder.setVideoSize(VIDEO_WIDTH, VIDEO_HEIGHT)
        recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
        recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        recorder.setAudioEncodingBitRate(AUDIO_BITRATE)
        recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
        // Write rotation metadata into the MP4 so players display the
        // video upright. Same formula as the JPEG case: sensor offset +
        // current device rotation, with front-camera mirror handling.
        recorder.setOrientationHint(orientationHint)
        return recorder
    }

    /** Compute the orientation hint to embed in the MP4 so players show
     *  the video upright. Sensor offset plus device rotation; front-
     *  camera output is mirrored, so the formula differs.  */
    private fun videoOrientation(context: Context, mgr: CameraManager, cameraId: String): Int {
        val chars = try {
            mgr.getCameraCharacteristics(cameraId)
        } catch (_: CameraAccessException) {
            return 0
        }
        val sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val front = chars.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        val deviceRotation = currentDisplayRotation(context)
        return if (front) {
            (sensor + deviceRotation) % 360
        } else {
            (sensor - deviceRotation + 360) % 360
        }
    }

    private fun currentDisplayRotation(context: Context): Int {
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? android.view.WindowManager
            ?: return 0
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.display?.rotation ?: Surface.ROTATION_0
        } else {
            wm.defaultDisplay.rotation
        }
        return when (r) {
            Surface.ROTATION_0 -> 0
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
}
