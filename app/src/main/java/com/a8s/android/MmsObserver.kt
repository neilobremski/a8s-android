package com.a8s.android

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.provider.Telephony
import java.io.File

/**
 * Observes content://mms for new messages and extracts media parts.
 * MMS messages (unlike RCS) are stored in the standard telephony
 * content provider and are accessible with READ_SMS permission.
 *
 * This is a supplementary path — the primary notification-listener
 * path handles both RCS and MMS text. This observer adds media
 * extraction from MMS specifically.
 */
class MmsObserver(
    private val context: Context,
    handler: Handler,
) : ContentObserver(handler) {

    private var lastProcessedId: Long = 0L
    private val processedIds = mutableSetOf<Long>()
    private val lock = Object()

    fun register() {
        lastProcessedId = getLatestMmsId()
        context.contentResolver.registerContentObserver(
            Telephony.Mms.CONTENT_URI,
            true,
            this
        )
        A8sAndroid.log("MmsObserver: registered (last ID=$lastProcessedId)")
    }

    fun unregister() {
        context.contentResolver.unregisterContentObserver(this)
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        super.onChange(selfChange, uri)
        Thread {
            Thread.sleep(2000)
            processNewMessages()
        }.start()
    }

    private fun processNewMessages() {
        synchronized(lock) {
            try {
                val cursor = context.contentResolver.query(
                    Telephony.Mms.CONTENT_URI,
                    arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX),
                    "${Telephony.Mms._ID} > ? AND ${Telephony.Mms.MESSAGE_BOX} = ?",
                    arrayOf(lastProcessedId.toString(), Telephony.Mms.MESSAGE_BOX_INBOX.toString()),
                    "${Telephony.Mms._ID} ASC"
                ) ?: return

                cursor.use { c ->
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id in processedIds) continue
                        processedIds += id
                        processOneMms(id)
                        lastProcessedId = id
                    }
                }
                if (processedIds.size > 100) {
                    val keep = processedIds.sorted().takeLast(50).toSet()
                    processedIds.clear()
                    processedIds += keep
                }
            } catch (e: Exception) {
                A8sAndroid.log("MmsObserver: error querying MMS: ${e.message}")
            }
        }
    }

    private fun processOneMms(mmsId: Long) {
        val sender = getMmsSender(mmsId) ?: return
        val text = getMmsText(mmsId)
        val mediaFiles = getMmsMedia(mmsId)

        if (text.isEmpty() && mediaFiles.isEmpty()) return

        val body = text.ifEmpty { "[MMS media]" }
        A8sAndroid.log("MmsObserver: MMS from $sender: ${body.take(100)} [+${mediaFiles.size} media]")

        if (mediaFiles.isNotEmpty()) {
            A8sService.instance?.publishIncoming(sender, body, mediaFiles)
        }
    }

    private fun getMmsSender(mmsId: Long): String? {
        try {
            val uri = Uri.parse("content://mms/$mmsId/addr")
            val cursor = context.contentResolver.query(
                uri,
                arrayOf(Telephony.Mms.Addr.ADDRESS, Telephony.Mms.Addr.TYPE),
                "${Telephony.Mms.Addr.TYPE} = ?",
                arrayOf("137"),  // PduHeaders.FROM address type
                null
            ) ?: return null

            cursor.use { c ->
                if (c.moveToFirst()) {
                    return c.getString(0)?.replace("[^0-9+]".toRegex(), "")
                }
            }
        } catch (e: Exception) {
            A8sAndroid.log("MmsObserver: error getting sender for MMS $mmsId: ${e.message}")
        }
        return null
    }

    private fun getMmsText(mmsId: Long): String {
        val textParts = StringBuilder()
        try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = context.contentResolver.query(
                partUri,
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.TEXT),
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(mmsId.toString()),
                null
            ) ?: return ""

            cursor.use { c ->
                while (c.moveToNext()) {
                    val contentType = c.getString(1) ?: continue
                    if (contentType == "text/plain") {
                        val text = c.getString(2)
                        if (!text.isNullOrEmpty()) {
                            if (textParts.isNotEmpty()) textParts.append("\n")
                            textParts.append(text)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            A8sAndroid.log("MmsObserver: error reading text parts: ${e.message}")
        }
        return textParts.toString()
    }

    private fun getMmsMedia(mmsId: Long): List<File> {
        val files = mutableListOf<File>()
        val destDir = File(context.cacheDir, "mms-inbound")
        destDir.mkdirs()

        try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = context.contentResolver.query(
                partUri,
                arrayOf(Telephony.Mms.Part._ID, Telephony.Mms.Part.CONTENT_TYPE, Telephony.Mms.Part.NAME),
                "${Telephony.Mms.Part.MSG_ID} = ?",
                arrayOf(mmsId.toString()),
                null
            ) ?: return files

            cursor.use { c ->
                while (c.moveToNext()) {
                    val partId = c.getLong(0)
                    val contentType = c.getString(1) ?: continue
                    val name = c.getString(2) ?: "part-$partId"

                    if (!contentType.startsWith("image/") &&
                        !contentType.startsWith("video/") &&
                        !contentType.startsWith("audio/")) {
                        continue
                    }

                    val ext = MediaExtractor.mimeTypeToExtension(contentType)
                    val dest = File(destDir, "${name.substringBeforeLast(".")}.$ext")

                    try {
                        val partDataUri = Uri.parse("content://mms/part/$partId")
                        context.contentResolver.openInputStream(partDataUri)?.use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        if (dest.length() > 0) {
                            files += dest
                            A8sAndroid.log("MmsObserver: extracted $contentType part (${dest.length()} bytes)")
                        } else {
                            dest.delete()
                        }
                    } catch (e: Exception) {
                        A8sAndroid.log("MmsObserver: error reading part $partId: ${e.message}")
                        dest.delete()
                    }
                }
            }
        } catch (e: Exception) {
            A8sAndroid.log("MmsObserver: error querying parts: ${e.message}")
        }
        return files
    }

    private fun getLatestMmsId(): Long {
        try {
            val cursor = context.contentResolver.query(
                Telephony.Mms.CONTENT_URI,
                arrayOf(Telephony.Mms._ID),
                null, null,
                "${Telephony.Mms._ID} DESC LIMIT 1"
            ) ?: return 0L

            cursor.use { c ->
                if (c.moveToFirst()) return c.getLong(0)
            }
        } catch (e: Exception) {
            A8sAndroid.log("MmsObserver: error getting latest ID: ${e.message}")
        }
        return 0L
    }
}
