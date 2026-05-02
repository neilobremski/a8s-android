package com.a8s.android

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Capture one frame from a `MediaProjection` and write it to a PNG file.
 *
 * Built fresh per call: a `VirtualDisplay` + `ImageReader` for one
 * frame, then released. We don't hold the projection alive between
 * captures because the OS's media-projection notification stays active
 * the whole time the projection is held — looping through capture+release
 * keeps the user's status bar quiet between shots.
 */
class Screenshot(
    private val context: Context,
    private val projection: MediaProjection,
) {

    /**
     * Capture one frame and write to `dest` as PNG. Returns true on
     * success. Caller is responsible for releasing the projection
     * itself if it isn't going to reuse this object.
     */
    fun capture(dest: File, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val thread = HandlerThread("a8s-screenshot").apply { start() }
        val handler = Handler(thread.looper)

        val ready = CountDownLatch(1)
        reader.setOnImageAvailableListener({ ready.countDown() }, handler)

        val virtualDisplay = projection.createVirtualDisplay(
            "a8s-screenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null,
        )

        try {
            if (!ready.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
            val image = reader.acquireLatestImage() ?: return false
            try {
                val plane = image.planes[0]
                val buffer = plane.buffer
                val pixelStride = plane.pixelStride
                val rowStride = plane.rowStride
                val rowPadding = rowStride - pixelStride * width
                val bitmap = Bitmap.createBitmap(
                    width + rowPadding / pixelStride, height,
                    Bitmap.Config.ARGB_8888,
                )
                bitmap.copyPixelsFromBuffer(buffer)
                val cropped = if (rowPadding == 0) bitmap
                    else Bitmap.createBitmap(bitmap, 0, 0, width, height)
                dest.parentFile?.mkdirs()
                FileOutputStream(dest).use { out ->
                    cropped.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, out)
                }
                return true
            } finally {
                image.close()
            }
        } finally {
            virtualDisplay.release()
            reader.close()
            thread.quitSafely()
        }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS: Long = 3000
        const val PNG_QUALITY: Int = 100
    }
}
