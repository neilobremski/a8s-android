package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir)
        assertEquals(1, results.size)
        assertNotNull(results[0].file)
        assertEquals("photo.jpg", results[0].file!!.name)
        assertNull(results[0].fallbackUrl)
    }

    @Test
    fun `falls back to URL when no service matches`() {
        val svc = FakeService("t", "other.org")
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")))
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir)
        assertEquals(1, results.size)
        assertNull(results[0].file)
        assertEquals("https://tempfile.org/abc/", results[0].fallbackUrl)
    }

    @Test
    fun `falls back to URL on service exception`() {
        val svc = FailingService("t")
        val files = listOf(EnvelopeFile("photo.jpg", listOf("https://tempfile.org/abc/")))
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir)
        assertNull(results[0].file)
        assertEquals("https://tempfile.org/abc/", results[0].fallbackUrl)
    }

    @Test
    fun `no storage URLs yields null file and null fallback`() {
        val files = listOf(EnvelopeFile("local.txt", emptyList()))
        val results = FileDownloader.downloadFiles(files, emptyList(), tempDir)
        assertNull(results[0].file)
        assertNull(results[0].fallbackUrl)
    }

    @Test
    fun `tries second URL when first service rejects`() {
        val svc = FakeService("t", "backup.org")
        val files = listOf(
            EnvelopeFile("doc.pdf", listOf("https://primary.org/x/", "https://backup.org/y/")),
        )
        val results = FileDownloader.downloadFiles(files, listOf(svc), tempDir)
        assertNotNull(results[0].file)
        assertEquals("doc.pdf", results[0].file!!.name)
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
