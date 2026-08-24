package com.a8s.android

import android.app.Notification
import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Build
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
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
            tryAudioContentsMedia(context, sbn, destDir)?.let { results += it }
        }

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
            NotificationCompat.MessagingStyle
                .extractMessagingStyleFromNotification(sbn.notification)
        } catch (_: Exception) {
            null
        } ?: return null

        for (msg in style.messages.reversed()) {
            val dataUri = msg.dataUri ?: continue
            val mimeType = msg.dataMimeType ?: "application/octet-stream"
            copyContentUri(context, dataUri, mimeType, "MessagingStyle.dataUri", destDir)?.let { return it }
        }
        return null
    }

    private fun tryAudioContentsMedia(
        context: Context,
        sbn: StatusBarNotification,
        destDir: File,
    ): ExtractedMedia? {
        val extras = sbn.notification.extras ?: return null
        val parcelledUri = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                extras.getParcelable(Notification.EXTRA_AUDIO_CONTENTS_URI, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable<Uri>(Notification.EXTRA_AUDIO_CONTENTS_URI)
            }
        } catch (_: RuntimeException) {
            null
        }
        val uri = parcelledUri ?: try {
            extras.getString(Notification.EXTRA_AUDIO_CONTENTS_URI)
                ?.takeIf { it.isNotBlank() }
                ?.let(Uri::parse)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val mimeType = try {
            context.contentResolver.getType(uri)
        } catch (_: RuntimeException) {
            null
        } ?: "audio/*"
        return copyContentUri(context, uri, mimeType, "Notification.audioContents", destDir)
    }

    private fun copyContentUri(
        context: Context,
        uri: Uri,
        mimeType: String,
        strategy: String,
        destDir: File,
    ): ExtractedMedia? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT || uri.authority.isNullOrBlank()) {
            A8sAndroid.log("MediaExtract: ignored invalid $strategy URI (scheme=${uri.scheme ?: "none"})")
            return null
        }
        val dest = File(destDir, "media-${System.currentTimeMillis()}.${mimeTypeToExtension(mimeType)}")
        var complete = false
        return try {
            val input = context.contentResolver.openInputStream(uri) ?: return null
            dest.parentFile?.mkdirs()
            input.use { source ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(COPY_BUFFER_BYTES)
                    var total = 0L
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_MEDIA_BYTES) throw MediaTooLargeException()
                        output.write(buffer, 0, read)
                    }
                }
            }
            if (dest.length() == 0L) return null
            complete = true
            ExtractedMedia(dest, mimeType, strategy)
        } catch (_: SecurityException) {
            A8sAndroid.log("MediaExtract: access denied for $strategy content URI")
            null
        } catch (_: MediaTooLargeException) {
            A8sAndroid.log("MediaExtract: $strategy media exceeds 50 MiB cap")
            null
        } catch (_: Exception) {
            A8sAndroid.log("MediaExtract: failed reading $strategy content URI")
            null
        } finally {
            if (!complete) dest.delete()
        }
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
        } catch (_: Exception) {
            A8sAndroid.log("MediaExtract: bitmap save failed ($strategy)")
        } finally {
            bitmap.recycle()
        }
        dest.delete()
        return null
    }

    internal fun mimeTypeToExtension(mimeType: String): String = when {
        mimeType.startsWith("image/jpeg") -> "jpg"
        mimeType.startsWith("image/png") -> "png"
        mimeType.startsWith("image/gif") -> "gif"
        mimeType.startsWith("image/webp") -> "webp"
        mimeType.startsWith("image/heic") -> "heic"
        mimeType.startsWith("image/heif") -> "heif"
        mimeType.startsWith("image/") -> "jpg"
        mimeType.startsWith("video/mp4") -> "mp4"
        mimeType.startsWith("video/") -> "mp4"
        mimeType.startsWith("audio/amr") -> "amr"
        mimeType.startsWith("audio/wav") || mimeType.startsWith("audio/x-wav") -> "wav"
        mimeType.startsWith("audio/mpeg") -> "mp3"
        mimeType.startsWith("audio/ogg") -> "ogg"
        mimeType.startsWith("audio/aac") -> "aac"
        mimeType.startsWith("audio/") -> "m4a"
        else -> "bin"
    }

    private class MediaTooLargeException : Exception()

    private const val COPY_BUFFER_BYTES = 32 * 1024
    private const val MAX_MEDIA_BYTES = 50L * 1024L * 1024L
}
