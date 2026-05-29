package com.a8s.android

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.service.notification.StatusBarNotification
import java.io.File

object MediaExtractor {

    data class ExtractedMedia(
        val file: File,
        val mimeType: String,
        val strategy: String,
    )

    fun extract(
        context: Context,
        sbn: StatusBarNotification,
        destDir: File,
    ): List<ExtractedMedia> {
        val results = mutableListOf<ExtractedMedia>()

        tryMessagingStyleMedia(context, sbn, destDir)?.let { results += it }

        if (results.isEmpty()) {
            tryBigPicture(context, sbn, destDir)?.let { results += it }
        }

        if (results.isEmpty()) {
            tryLargeIconBig(sbn, destDir)?.let { results += it }
        }

        return results
    }

    private fun tryMessagingStyleMedia(
        context: Context,
        sbn: StatusBarNotification,
        destDir: File,
    ): ExtractedMedia? {
        val style = try {
            Notification.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification)
        } catch (_: Exception) {
            null
        } ?: return null

        for (msg in style.messages.reversed()) {
            val dataUri = msg.dataUri ?: continue
            val mimeType = msg.dataMimeType ?: "application/octet-stream"

            val ext = mimeTypeToExtension(mimeType)
            val dest = File(destDir, "media-${System.currentTimeMillis()}.$ext")

            try {
                context.contentResolver.openInputStream(dataUri)?.use { input ->
                    dest.parentFile?.mkdirs()
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                if (dest.length() > 0) {
                    return ExtractedMedia(dest, mimeType, "MessagingStyle.dataUri")
                }
                dest.delete()
            } catch (e: SecurityException) {
                A8sAndroid.log("MediaExtract: SecurityException reading $dataUri (${e.message})")
                dest.delete()
            } catch (e: Exception) {
                A8sAndroid.log("MediaExtract: Failed reading $dataUri (${e.message})")
                dest.delete()
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun tryBigPicture(
        context: Context,
        sbn: StatusBarNotification,
        destDir: File,
    ): ExtractedMedia? {
        val extras = sbn.notification.extras

        val bitmap = extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
        if (bitmap != null) {
            return saveBitmap(bitmap, destDir, "BigPictureStyle")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val icon = extras.getParcelable<android.graphics.drawable.Icon>(
                Notification.EXTRA_PICTURE_ICON
            )
            if (icon != null) {
                val iconBitmap = try {
                    icon.loadDrawable(context)?.let { drawable ->
                        val w = drawable.intrinsicWidth.coerceAtLeast(1)
                        val h = drawable.intrinsicHeight.coerceAtLeast(1)
                        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                        val canvas = Canvas(bmp)
                        drawable.setBounds(0, 0, canvas.width, canvas.height)
                        drawable.draw(canvas)
                        bmp
                    }
                } catch (_: Exception) {
                    null
                }
                if (iconBitmap != null) {
                    return saveBitmap(iconBitmap, destDir, "BigPictureIcon")
                }
            }
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun tryLargeIconBig(sbn: StatusBarNotification, destDir: File): ExtractedMedia? {
        val extras = sbn.notification.extras
        val largeIcon = extras.getParcelable<Bitmap>(Notification.EXTRA_LARGE_ICON_BIG)
        if (largeIcon != null && largeIcon.width > 128 && largeIcon.height > 128) {
            return saveBitmap(largeIcon, destDir, "LargeIconBig")
        }
        return null
    }

    private fun saveBitmap(bitmap: Bitmap, destDir: File, strategy: String): ExtractedMedia? {
        val dest = File(destDir, "media-${System.currentTimeMillis()}.png")
        dest.parentFile?.mkdirs()
        try {
            dest.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 90, out)
            }
            if (dest.length() > 0) {
                return ExtractedMedia(dest, "image/png", strategy)
            }
        } catch (e: Exception) {
            A8sAndroid.log("MediaExtract: bitmap save failed ($strategy): ${e.message}")
        }
        dest.delete()
        return null
    }

    private fun mimeTypeToExtension(mimeType: String): String = when {
        mimeType.startsWith("image/jpeg") -> "jpg"
        mimeType.startsWith("image/png") -> "png"
        mimeType.startsWith("image/gif") -> "gif"
        mimeType.startsWith("image/webp") -> "webp"
        mimeType.startsWith("video/mp4") -> "mp4"
        mimeType.startsWith("video/") -> "mp4"
        mimeType.startsWith("audio/") -> "m4a"
        else -> "bin"
    }
}
