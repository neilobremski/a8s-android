package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class FileDownloaderTest {

    @TempDir
    lateinit var tempDir: File

    private class FakeService(
        override val id: String,
        private val acceptHost: String,
        private val content: ByteArray = "fake-content".toByteArray(),
    ) : StorageService {
        override fun store(file: File): String = throw UnsupportedOperationException()
        override fun retrieve(url: String, dest: File): Boolean {
            if (!url.contains(acceptHost)) return false
            dest.parentFile?.mkdirs()
            dest.writeBytes(content)
            return true
        }
    }

    /** No network in unit tests: the https fallback is stubbed per test. */
    private val noHttp: HttpFetch = { _, _ -> HttpGet.Result.Failed("stubbed: offline") }

    private class FailingService(override val id: String) : StorageService {
        override fun store(file: File): String = throw UnsupportedOperationException()
        override fun retrieve(url: String, dest: File): Boolean {
            throw StorageException("network error")
        }
    }

    @Test
    fun `downloads file from matching service`() {
        val svc = FakeService("t", "tempfile.org")
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")))
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir, noHttp)
        assertEquals(1, results.size)
        assertNotNull(results[0].file)
        assertEquals("photo.jpg", results[0].file!!.name)
        assertNull(results[0].fallbackUrl)
    }

    @Test
    fun `falls back to URL when no service matches`() {
        val svc = FakeService("t", "other.org")
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")))
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir, noHttp)
        assertEquals(1, results.size)
        assertNull(results[0].file)
        assertEquals("https://tempfile.org/abc/", results[0].fallbackUrl)
    }

    @Test
    fun `falls back to URL on service exception`() {
        val svc = FailingService("t")
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")))
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir, noHttp)
        assertNull(results[0].file)
        assertEquals("https://tempfile.org/abc/", results[0].fallbackUrl)
    }

    @Test
    fun `no storage URLs yields NO_URLS outcome`() {
        val files = listOf(EnvelopeFile("local.txt", emptyList()))
        val results = FileDownloader.downloadFiles(files, emptyList(), tempDir, noHttp)
        assertNull(results[0].file)
        assertNull(results[0].fallbackUrl)
        assertEquals(FileDownloader.DownloadOutcome.NO_URLS, results[0].outcome)
    }

    @Test
    fun `tries second URL when first service rejects`() {
        val svc = FakeService("t", "backup.org")
        val files = listOf(
            EnvelopeFile("doc.pdf", listOf("https://primary.org/x/", "https://backup.org/y/")),
        )
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir, noHttp)
        assertNotNull(results[0].file)
        assertEquals("doc.pdf", results[0].file!!.name)
    }

    @Test
    fun `falls back to a plain https GET when no service claims the URL`() {
        // The point of the 0.1.57 receiver contract: a sender can add a
        // storage backend without every receiver being reconfigured.
        val svc = FakeService("t", "other.org")
        val files = listOf(
            EnvelopeFile("memo.m4a", listOf("https://drive.usercontent.google.com/download?id=X")),
        )
        var asked = ""
        val fetch: HttpFetch = { url, dest ->
            asked = url
            dest.writeBytes("real-bytes".toByteArray())
            HttpGet.Result.Ok
        }
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir, fetch)
        assertEquals("https://drive.usercontent.google.com/download?id=X", asked)
        assertNotNull(results[0].file)
        assertEquals("memo.m4a", results[0].file!!.name)
        assertEquals("real-bytes", results[0].file!!.readText())
        assertNull(results[0].fallbackUrl)
    }

    @Test
    fun `a configured service is preferred over the plain GET`() {
        val svc = FakeService("t", "tempfile.org")
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")))
        var httpCalled = false
        val fetch: HttpFetch = { _, _ ->
            httpCalled = true
            HttpGet.Result.Ok
        }
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir, fetch)
        assertEquals(false, httpCalled)
        assertEquals("via t", results[0].detail)
    }

    @Test
    fun `a traversing filename never reaches the network or the disk`() {
        val escaped = File(tempDir.parentFile, "ESCAPED.txt")
        escaped.delete()
        var httpCalled = false
        val fetch: HttpFetch = { _, dest ->
            httpCalled = true
            dest.writeBytes("pwned".toByteArray())
            HttpGet.Result.Ok
        }
        val files = listOf(
            EnvelopeFile("../ESCAPED.txt", listOf("https://evil.example/x")),
        )
        val results = FileDownloader.downloadFiles(files, emptyList(), tempDir, fetch)
        assertEquals(false, httpCalled)
        assertNull(results[0].file)
        assertEquals(false, escaped.exists())
    }

    @Test
    fun `the failure detail names the reason, not just the URL`() {
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://store.example/x")))
        val fetch: HttpFetch = { _, _ -> HttpGet.Result.Failed("HTTP 403 for https://store.example/x") }
        val results = FileDownloader.downloadFiles(files, emptyList(), tempDir, fetch)
        assertEquals("https://store.example/x", results[0].fallbackUrl)
        assertTrue(results[0].detail.contains("403"))
    }

    @Test
    fun `buildSmsBody appends fallback URLs`() {
        val results = listOf(
            FileDownloader.DownloadResult(File("/tmp/a.jpg"), null),
            FileDownloader.DownloadResult(null, "https://tempfile.org/xyz/"),
        )
        val body = FileDownloader.buildSmsBody("hello", results)
        assertEquals("hello\nhttps://tempfile.org/xyz/", body)
    }

    @Test
    fun `buildSmsBody unchanged when all files downloaded`() {
        val results = listOf(
            FileDownloader.DownloadResult(File("/tmp/a.jpg"), null),
        )
        assertEquals("hello", FileDownloader.buildSmsBody("hello", results))
    }

    @Test
    fun `buildSmsBody unchanged for empty results`() {
        assertEquals("hi", FileDownloader.buildSmsBody("hi", emptyList()))
    }
}
