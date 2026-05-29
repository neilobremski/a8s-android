package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileDownloadRegressionTest {

    @TempDir
    lateinit var tempDir: File

    private fun config(
        device: String = "my-phone",
        phonebook: Map<String, String> = mapOf("Clover" to "+15550001111"),
    ): A8sAndroid.Config = A8sAndroid.Config(
        device = device,
        phonebook = phonebook,
        remotes = mapOf(
            "default" to RemoteConfig(
                broker = "ssl://broker:8883",
                topic = "t",
                username = "u",
                password = "p",
            ),
        ),
        services = emptyList(),
    )

    @Test
    fun `non-command content returns NotACommand`() {
        val payload = JSONObject().apply {
            put("to", "my-phone")
            put("from", "Clover")
            put("content", "see attached")
        }.toString()

        val route = decideRoute(payload, config())
        assertTrue(route is MqttRoute.NotACommand)
        assertEquals("Clover", (route as MqttRoute.NotACommand).sender)
    }

    @Test
    fun `parseEnvelopeFiles preserves storage URLs from envelope JSON`() {
        val json = JSONObject().apply {
            put("files", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", "photo.jpg")
                    put("storage", org.json.JSONArray().apply {
                        put("https://tempfile.org/abc123/")
                    })
                })
            })
        }

        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("photo.jpg", files[0].filename)
        assertEquals("https://tempfile.org/abc123/", files[0].storageUrls[0])
    }

    @Test
    fun `message to non-device with files is dropped`() {
        val payload = JSONObject().apply {
            put("to", "Clover")
            put("from", "gerry")
            put("content", "doc here")
            put("files", org.json.JSONArray().apply {
                put(JSONObject().apply {
                    put("filename", "report.pdf")
                    put("storage", org.json.JSONArray().apply {
                        put("https://tempfile.org/def456/")
                    })
                })
            })
        }.toString()

        val route = decideRoute(payload, config())
        assertTrue(route is MqttRoute.Drop)
    }

    private class FakeStorageService(
        override val id: String,
        private val content: ByteArray = "file-bytes".toByteArray(),
    ) : StorageService {
        override fun store(file: File): String =
            throw UnsupportedOperationException()
        override fun retrieve(url: String, dest: File): Boolean {
            dest.parentFile?.mkdirs()
            dest.writeBytes(content)
            return true
        }
    }

    @Test
    fun `file download resolves storage URL to local file`() {
        val svc = FakeStorageService("tempfile_org")
        val files = listOf(
            EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")),
        )
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir)
        assertEquals(1, results.size)
        assertNotNull(results[0].file)
        assertEquals("photo.jpg", results[0].file!!.name)
        assertNull(results[0].fallbackUrl)
    }

    @Test
    fun `without download support URL appears as fallback text`() {
        // When no service can handle the URL, it falls back to
        // including the raw URL in the SMS body — the old behavior
        // equivalent.
        val files = listOf(
            EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")),
        )
        val results = FileDownloader.downloadFiles(files, emptyList(), tempDir)
        assertNull(results[0].file)
        assertEquals("https://tempfile.org/abc/", results[0].fallbackUrl)
    }

    @Test
    fun `sms body includes fallback URL when download fails`() {
        val results = listOf(
            FileDownloader.DownloadResult(null, "https://tempfile.org/abc/"),
        )
        val body = FileDownloader.buildSmsBody("hello", results)
        assertEquals("hello\nhttps://tempfile.org/abc/", body)
    }

    @Test
    fun `sms body is clean when all files downloaded successfully`() {
        val results = listOf(
            FileDownloader.DownloadResult(File(tempDir, "photo.jpg"), null),
        )
        val body = FileDownloader.buildSmsBody("hello", results)
        assertEquals("hello", body)
    }
}
