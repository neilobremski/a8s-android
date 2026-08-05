package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The scheme rules are checked here. The transfer itself is exercised
 * indirectly through [FileDownloaderTest] with an injected fetch, because a
 * real https server needs a certificate and the rules under test are branches
 * that a plaintext server would never reach.
 */
class HttpGetTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `https is recognized and other schemes are not`() {
        assertTrue(HttpGet.isHttps("https://store.example/object"))
        assertTrue(HttpGet.isHttps("  https://store.example/object  "))
        assertEquals(false, HttpGet.isHttps("http://store.example/object"))
        assertEquals(false, HttpGet.isHttps("s3://bucket/key"))
        assertEquals(false, HttpGet.isHttps("file:///etc/passwd"))
        assertEquals(false, HttpGet.isHttps("not a url"))
    }

    @Test
    fun `plaintext http is declined rather than fetched`() {
        // NotHttps is distinct from Failed: it means "not ours", so a caller
        // can try another route instead of reporting a transfer error.
        val result = HttpGet.download("http://127.0.0.1:1/x", File(tempDir, "out.bin"))
        assertEquals(HttpGet.Result.NotHttps, result)
        assertEquals(false, File(tempDir, "out.bin").exists())
    }

    @Test
    fun `a non-http scheme is declined`() {
        val result = HttpGet.download("file:///etc/passwd", File(tempDir, "out.bin"))
        assertEquals(HttpGet.Result.NotHttps, result)
    }

    @Test
    fun `an unreachable host fails without leaving a partial file`() {
        val dest = File(tempDir, "out.bin")
        val result = HttpGet.download("https://127.0.0.1:1/x", dest, timeoutS = 2)
        assertTrue(result is HttpGet.Result.Failed)
        assertEquals(false, dest.exists())
        assertEquals(false, File(tempDir, "out.bin.part").exists())
    }

    @Test
    fun `the redirect limit is small enough to bound a hostile chain`() {
        // The sender of an envelope picks the first URL. Without a limit it
        // picks the last one too.
        assertTrue(HttpGet.MAX_REDIRECTS in 1..5)
    }
}
