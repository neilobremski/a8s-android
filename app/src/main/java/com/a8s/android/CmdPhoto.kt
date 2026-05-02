package com.a8s.android

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import android.view.Surface
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `/photo [front|back]` — open Camera2 headlessly, take one JPEG, save
 * it to `cacheDir/photos/photo-<ts>.jpg`, and reply with the file
 * attached.
 *
 * Runs on a worker thread; the Camera2 callbacks themselves are
 * dispatched onto a per-call HandlerThread that's torn down after the
 * capture completes (no leaked threads between shots).
 */
object CmdPhoto {

    private const val MAX_WIDTH = 1920
    private const val MAX_HEIGHT = 1080
    private const val JPEG_QUALITY = 70
    private const val CAPTURE_TIMEOUT_S = 15L

    fun run(service: A8sService, config: A8sAndroid.Config, cmd: MqttRoute.Command) {
        val facing = CmdHelpers.parsePhotoFacing(cmd.args)
        val context: Context = service
        val mgr = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (mgr == null) {
            service.replyToOwner(config, cmd.owner, "Photo failed: camera service unavailable")
            return
        }
        val cameraId = findCameraId(mgr, facing)
        if (cameraId == null) {
            service.replyToOwner(config, cmd.owner, "Photo failed: no $facing camera found")
            return
        }

        val dest = File(File(context.cacheDir, "photos"), "photo-${System.currentTimeMillis()}.jpg")
        dest.parentFile?.mkdirs()
        val thread = HandlerThread("a8s-photo").apply { start() }
        val handler = Handler(thread.looper)
        try {
            val ok = capture(mgr, cameraId, handler, dest)
            if (!ok || dest.length() == 0L) {
                service.replyToOwner(config, cmd.owner, "Photo failed: no image captured")
                return
            }
            A8sAndroid.log("Photo captured: ${dest.length()} bytes (${facing.name.lowercase()})")
            service.replyToOwner(
                config, cmd.owner,
                "Photo (${CmdHelpers.humanSize(dest.length())}, ${facing.name.lowercase()})",
                files = listOf(dest),
            )
        } catch (e: Exception) {
            A8sAndroid.log("Photo failed: ${e.message}")
            service.replyToOwner(config, cmd.owner, "Photo failed: ${e.message}")
        } finally {
            thread.quitSafely()
        }
    }

    private fun findCameraId(mgr: CameraManager, facing: CmdHelpers.CameraFacing): String? {
        val target = when (facing) {
            CmdHelpers.CameraFacing.FRONT -> CameraCharacteristics.LENS_FACING_FRONT
            CmdHelpers.CameraFacing.BACK -> CameraCharacteristics.LENS_FACING_BACK
        }
        return try {
            mgr.cameraIdList.firstOrNull { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == target
            }
        } catch (_: CameraAccessException) {
            null
        }
    }

    @Suppress("LongMethod", "ReturnCount")
    private fun capture(
        mgr: CameraManager,
        cameraId: String,
        handler: Handler,
        dest: File,
    ): Boolean {
        val chars = mgr.getCameraCharacteristics(cameraId)
        val configs = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return false
        val outputSize = chooseOutputSize(configs.getOutputSizes(ImageFormat.JPEG))
        val reader = ImageReader.newInstance(outputSize.width, outputSize.height, ImageFormat.JPEG, 1)

        var device: CameraDevice? = null
        val openLatch = CountDownLatch(1)
        val captureLatch = CountDownLatch(1)
        var jpegBytes: ByteArray? = null
        var error: String? = null

        try {
            mgr.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    openLatch.countDown()
                }
                override fun onDisconnected(camera: CameraDevice) {
                    error = "camera disconnected"; camera.close()
                    openLatch.countDown(); captureLatch.countDown()
                }
                override fun onError(camera: CameraDevice, errCode: Int) {
                    error = "camera open error: $errCode"; camera.close()
                    openLatch.countDown(); captureLatch.countDown()
                }
            }, handler)
        } catch (e: SecurityException) {
            A8sAndroid.log("Camera permission denied: ${e.message}")
            reader.close()
            return false
        }

        if (!openLatch.await(CAPTURE_TIMEOUT_S, TimeUnit.SECONDS) || device == null) {
            reader.close()
            return false
        }

        reader.setOnImageAvailableListener({ r ->
            val img = r.acquireLatestImage()
            if (img != null) {
                try {
                    val buf = img.planes[0].buffer
                    val bytes = ByteArray(buf.remaining())
                    buf.get(bytes)
                    jpegBytes = bytes
                } finally {
                    img.close()
                }
            }
            captureLatch.countDown()
        }, handler)

        val d = device!!
        val sessionLatch = CountDownLatch(1)
        try {
            @Suppress("DEPRECATION")
            d.createCaptureSession(listOf<Surface>(reader.surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        try {
                            val req = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                                addTarget(reader.surface)
                                set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY.toByte())
                                set(
                                    CaptureRequest.CONTROL_AF_MODE,
                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                )
                                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                            }.build()
                            session.capture(req, object : CameraCaptureSession.CaptureCallback() {
                                override fun onCaptureFailed(
                                    s: CameraCaptureSession,
                                    request: CaptureRequest,
                                    failure: CaptureFailure,
                                ) {
                                    error = "capture failed: ${failure.reason}"
                                    captureLatch.countDown()
                                }
                            }, handler)
                        } catch (e: CameraAccessException) {
                            error = "capture request failed: ${e.message}"
                            captureLatch.countDown()
                        }
                        sessionLatch.countDown()
                    }
                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        error = "session configure failed"
                        sessionLatch.countDown(); captureLatch.countDown()
                    }
                }, handler)

            if (!sessionLatch.await(CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)) return false
            if (!captureLatch.await(CAPTURE_TIMEOUT_S, TimeUnit.SECONDS)) return false
            val bytes = jpegBytes ?: run {
                A8sAndroid.log("Camera capture: ${error ?: "no bytes"}")
                return false
            }
            FileOutputStream(dest).use { it.write(bytes) }
            return true
        } finally {
            d.close()
            reader.close()
        }
    }

    private fun chooseOutputSize(available: Array<Size>): Size {
        if (available.isEmpty()) error("camera has no JPEG output sizes")
        val capped = available.filter { it.width <= MAX_WIDTH && it.height <= MAX_HEIGHT }
        return if (capped.isNotEmpty()) {
            capped.maxByOrNull { it.width.toLong() * it.height.toLong() }!!
        } else {
            available.minByOrNull { it.width.toLong() * it.height.toLong() }!!
        }
    }
}
