package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class UpdaterTest {

    // ---------- compareVersions ----------

    @Test
    fun `compareVersions equal`() {
        assertEquals(0, Updater.compareVersions("1.0.0", "1.0.0"))
    }

    @Test
    fun `compareVersions less and greater by major`() {
        assertEquals(-1, Updater.compareVersions("1.0.0", "2.0.0"))
        assertEquals(1, Updater.compareVersions("2.0.0", "1.0.0"))
    }

    @Test
    fun `compareVersions handles double-digit minors`() {
        // "1.10.0" must be greater than "1.9.0" — naive lexical compare
        // would get this wrong.
        assertEquals(1, Updater.compareVersions("1.10.0", "1.9.0"))
        assertEquals(-1, Updater.compareVersions("1.9.0", "1.10.0"))
    }

    @Test
    fun `compareVersions trailing components default to zero`() {
        assertEquals(0, Updater.compareVersions("1.0", "1.0.0"))
        assertEquals(0, Updater.compareVersions("1", "1.0.0"))
        assertEquals(-1, Updater.compareVersions("1.0", "1.0.1"))
    }

    @Test
    fun `compareVersions falls back to lexical for non-numeric`() {
        assertEquals(-1, Updater.compareVersions("1.0.0-alpha", "1.0.0-beta"))
    }

    // ---------- parseReleaseJson ----------

    private val sampleReleaseJson = """
        {
          "tag_name": "v1.8.0",
          "published_at": "2026-05-02T03:00:00Z",
          "assets": [
            {
              "name": "Source code",
              "browser_download_url": "https://example.invalid/src.zip",
              "size": 1234
            },
            {
              "name": "a8s-android-1.8.0-debug.apk",
              "browser_download_url": "https://example.invalid/a8s-android-1.8.0-debug.apk",
              "size": 6094169
            }
          ]
        }
    """.trimIndent()

    @Test
    fun `parseReleaseJson picks the matching apk asset`() {
        val info = Updater.parseReleaseJson(sampleReleaseJson)!!
        assertEquals("v1.8.0", info.tagName)
        assertEquals("1.8.0", info.versionName)
        assertEquals("a8s-android-1.8.0-debug.apk", info.apkName)
        assertEquals("https://example.invalid/a8s-android-1.8.0-debug.apk", info.apkUrl)
        assertEquals(6094169L, info.sizeBytes)
        assertEquals("2026-05-02T03:00:00Z", info.publishedAt)
    }

    @Test
    fun `parseReleaseJson returns null when no matching asset`() {
        val json = """
            {"tag_name":"v1.8.0","assets":[
              {"name":"random.zip","browser_download_url":"x","size":1}
            ]}
        """.trimIndent()
        assertNull(Updater.parseReleaseJson(json))
    }

    @Test
    fun `parseReleaseJson tolerates missing tag`() {
        assertNull(Updater.parseReleaseJson("""{"assets":[]}"""))
    }

    @Test
    fun `parseReleaseJson returns null on invalid JSON`() {
        assertNull(Updater.parseReleaseJson("not json"))
    }

    // ---------- humanSize ----------

    @Test
    fun `humanSize picks bytes KB MB`() {
        assertEquals("0 B", Updater.humanSize(0))
        assertEquals("999 B", Updater.humanSize(999))
        assertTrue(Updater.humanSize(1500).endsWith("KB"))
        assertTrue(Updater.humanSize(2_500_000).endsWith("MB"))
    }

    @Test
    fun `humanSize handles unknown`() {
        assertEquals("?", Updater.humanSize(-1))
    }

    // ---------- renderCheck ----------

    @Test
    fun `renderCheck flags update available`() {
        val info = Updater.parseReleaseJson(sampleReleaseJson)!!
        val out = Updater.renderCheck("1.7.0", info)
        assertTrue(out.contains("UPDATE AVAILABLE"))
        assertTrue(out.contains("v1.7.0"))
        assertTrue(out.contains("v1.8.0"))
    }

    @Test
    fun `renderCheck flags up to date`() {
        val info = Updater.parseReleaseJson(sampleReleaseJson)!!
        val out = Updater.renderCheck("1.8.0", info)
        assertTrue(out.contains("up to date"))
    }
}
