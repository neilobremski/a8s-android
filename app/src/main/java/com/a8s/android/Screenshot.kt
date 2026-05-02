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

        // maxImages=4 — VirtualDisplay's first 1-2 frames after creation
        // are typically uninitialized (blank-buffer) before Android draws
        // the actual screen content into the surface. We let the buffer
        // accumulate a few frames, then `acquireLatestImage()` after a
        // short settle delay returns the newest one with real content.
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, MAX_BUFFERED_FRAMES)
        val thread = HandlerThread("a8s-screenshot").apply { start() }
        val handler = Handler(thread.looper)

        val firstFrame = CountDownLatch(1)
        reader.setOnImageAvailableListener({ firstFrame.countDown() }, handler)

        val virtualDisplay = projection.createVirtualDisplay(
            "a8s-screenshot",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null, null,
        )

        try {
            if (!firstFrame.await(timeoutMs, TimeUnit.MILLISECONDS)) return false
            // First frame fired — let a few more queue up so the buffer
            // contains real content (not the initial blank surface).
            Thread.sleep(SETTLE_MS)
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
        const val MAX_BUFFERED_FRAMES: Int = 4
        // ~12 frames at 60Hz — empirically enough for the system UI to
        // composite real content into the VirtualDisplay surface across
        // a range of devices. Too short and we capture a blank/initial
        // buffer; too long is just latency.
        const val SETTLE_MS: Long = 200
    }
}
