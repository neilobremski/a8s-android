package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CmdHelpersTest {

    // ── /send ─────────────────────────────────────────────────────────────

    @Test
    fun `parseSendArgs valid input`() {
        val result = CmdHelpers.parseSendArgs(listOf("+15551234567", "hey", "there"))
        assertEquals(CmdHelpers.SendParts("+15551234567", "hey there"), result)
    }

    @Test
    fun `parseSendArgs just a number returns null`() {
        assertNull(CmdHelpers.parseSendArgs(listOf("+15551234567")))
    }

    @Test
    fun `parseSendArgs empty returns null`() {
        assertNull(CmdHelpers.parseSendArgs(emptyList()))
    }

    @Test
    fun `parseSendArgs preserves multi-word body`() {
        val result = CmdHelpers.parseSendArgs(listOf("5550001111", "hello", "world", "how", "are", "you"))
        assertEquals("hello world how are you", result!!.body)
    }

    // ── /photo ────────────────────────────────────────────────────────────

    @Test
    fun `parsePhotoFacing defaults to back`() {
        assertEquals(CmdHelpers.CameraFacing.BACK, CmdHelpers.parsePhotoFacing(emptyList()))
    }

    @Test
    fun `parsePhotoFacing reads front`() {
        assertEquals(CmdHelpers.CameraFacing.FRONT, CmdHelpers.parsePhotoFacing(listOf("front")))
        assertEquals(CmdHelpers.CameraFacing.FRONT, CmdHelpers.parsePhotoFacing(listOf("FRONT")))
    }

    @Test
    fun `parsePhotoFacing unknown value falls back to back`() {
        assertEquals(CmdHelpers.CameraFacing.BACK, CmdHelpers.parsePhotoFacing(listOf("selfie")))
    }

    // ── /video ────────────────────────────────────────────────────────────

    @Test
    fun `parseVideoSeconds default`() {
        assertEquals(CmdHelpers.VIDEO_DEFAULT_SECONDS, CmdHelpers.parseVideoSeconds(emptyList()))
    }

    @Test
    fun `parseVideoSeconds clamps over 30 to 30`() {
        assertEquals(30, CmdHelpers.parseVideoSeconds(listOf("99")))
    }

    @Test
    fun `parseVideoSeconds clamps zero or negative to 1`() {
        assertEquals(1, CmdHelpers.parseVideoSeconds(listOf("0")))
        assertEquals(1, CmdHelpers.parseVideoSeconds(listOf("-5")))
    }

    @Test
    fun `parseVideoSeconds garbage uses default`() {
        assertEquals(CmdHelpers.VIDEO_DEFAULT_SECONDS, CmdHelpers.parseVideoSeconds(listOf("forever")))
    }

    // ── /audio ────────────────────────────────────────────────────────────

    @Test
    fun `parseAudioSeconds default`() {
        assertEquals(CmdHelpers.AUDIO_DEFAULT_SECONDS, CmdHelpers.parseAudioSeconds(emptyList()))
    }

    @Test
    fun `parseAudioSeconds clamps over 60 to 60`() {
        assertEquals(60, CmdHelpers.parseAudioSeconds(listOf("999")))
    }

    @Test
    fun `parseAudioSeconds clamps zero or negative to 1`() {
        assertEquals(1, CmdHelpers.parseAudioSeconds(listOf("0")))
        assertEquals(1, CmdHelpers.parseAudioSeconds(listOf("-3")))
    }

    @Test
    fun `parseAudioSeconds garbage uses default`() {
        assertEquals(CmdHelpers.AUDIO_DEFAULT_SECONDS, CmdHelpers.parseAudioSeconds(listOf("forever")))
    }

    // ── /location ─────────────────────────────────────────────────────────

    @Test
    fun `renderLocation has all expected fields`() {
        val s = CmdHelpers.LocationSnapshot(
            latitude = 47.6062,
            longitude = -122.3321,
            accuracyMeters = 12.5f,
            ageMs = 45_000,
            provider = "gps",
        )
        val out = CmdHelpers.renderLocation(s)
        assertTrue(out.contains("lat=47.606200"))
        assertTrue(out.contains("lng=-122.332100"))
        assertTrue(out.contains("accuracy=12.5m"))
        assertTrue(out.contains("age=45s"))
        assertTrue(out.contains("provider=gps"))
    }

    @Test
    fun `renderLocation handles null accuracy`() {
        val s = CmdHelpers.LocationSnapshot(0.0, 0.0, null, 0, "fused")
        assertTrue(CmdHelpers.renderLocation(s).contains("accuracy=?m"))
    }

    @Test
    fun `formatAge picks scale`() {
        assertEquals("12s", CmdHelpers.formatAge(12_000))
        assertEquals("3m", CmdHelpers.formatAge(3 * 60_000))
        assertEquals("2h5m", CmdHelpers.formatAge(2 * 3_600_000 + 5 * 60_000))
    }

    // ── /say ──────────────────────────────────────────────────────────────

    @Test
    fun `parseSayText joins args`() {
        assertEquals("hello world", CmdHelpers.parseSayText(listOf("hello", "world")))
    }

    @Test
    fun `parseSayText empty returns null`() {
        assertNull(CmdHelpers.parseSayText(emptyList()))
        assertNull(CmdHelpers.parseSayText(listOf("   ")))
    }

    // ── /notify ───────────────────────────────────────────────────────────

    @Test
    fun `parseNotifyArgs splits on pipe`() {
        val parts = CmdHelpers.parseNotifyArgs(listOf("Title", "stuff", "|", "body", "here"))
        // Args are joined first then split — joining "|" surrounded by spaces still finds the pipe.
        assertEquals("Title stuff", parts!!.title)
        assertEquals("body here", parts.body)
    }

    @Test
    fun `parseNotifyArgs no pipe falls back to default title`() {
        val parts = CmdHelpers.parseNotifyArgs(listOf("just", "a", "body"))
        assertEquals("a8s", parts!!.title)
        assertEquals("just a body", parts.body)
    }

    @Test
    fun `parseNotifyArgs empty returns null`() {
        assertNull(CmdHelpers.parseNotifyArgs(emptyList()))
    }

    @Test
    fun `parseNotifyArgs blank body after pipe returns null`() {
        assertNull(CmdHelpers.parseNotifyArgs(listOf("Title|")))
    }

    @Test
    fun `parseNotifyArgs blank title uses default`() {
        val parts = CmdHelpers.parseNotifyArgs(listOf("|body"))
        assertEquals("a8s", parts!!.title)
        assertEquals("body", parts.body)
    }

    // ── /ls ───────────────────────────────────────────────────────────────

    @Test
    fun `renderLs sorts directories first then by name`() {
        val out = CmdHelpers.renderLs(
            "/sdcard/Download",
            listOf(
                CmdHelpers.LsEntry("z.txt", 100, 0, isDirectory = false),
                CmdHelpers.LsEntry("a.txt", 50, 0, isDirectory = false),
                CmdHelpers.LsEntry("subdir", 0, 0, isDirectory = true),
            ),
        )
        val lines = out.split("\n")
        assertTrue(lines[0].contains("3 entries"))
        assertTrue(lines[1].startsWith("d "))
        assertTrue(lines[1].contains("subdir"))
        assertTrue(lines[2].contains("a.txt"))
        assertTrue(lines[3].contains("z.txt"))
    }

    @Test
    fun `renderLs empty directory shows zero entries`() {
        val out = CmdHelpers.renderLs("/empty", emptyList())
        assertEquals("/empty (0 entries)", out)
    }

    @Test
    fun `humanSize bytes`() {
        assertEquals("0B", CmdHelpers.humanSize(0))
        assertEquals("512B", CmdHelpers.humanSize(512))
        assertEquals("1.0KB", CmdHelpers.humanSize(1024))
        assertEquals("1.0MB", CmdHelpers.humanSize(1024 * 1024))
    }

    // ── /cat ──────────────────────────────────────────────────────────────

    @Test
    fun `looksLikeText accepts ascii`() {
        assertTrue(CmdHelpers.looksLikeText("hello\nworld\t!".toByteArray()))
    }

    @Test
    fun `looksLikeText rejects nul byte`() {
        assertFalse(CmdHelpers.looksLikeText(byteArrayOf('a'.code.toByte(), 0, 'b'.code.toByte())))
    }

    @Test
    fun `looksLikeText rejects control bytes`() {
        // 0x01 is non-printable, non-whitespace.
        assertFalse(CmdHelpers.looksLikeText(byteArrayOf(0x01, 0x02, 0x03)))
    }

    @Test
    fun `looksLikeText empty is text`() {
        assertTrue(CmdHelpers.looksLikeText(ByteArray(0)))
    }

    // ── known-commands list ──────────────────────────────────────────────

    @Test
    fun `known commands list covers every new verb`() {
        val joined = CmdHelpers.KNOWN_COMMANDS.joinToString(" ")
        for (verb in listOf(
            "/send", "/photo", "/video", "/location", "/say", "/notify", "/ls", "/cat", "/rm",
            "/tap", "/longtap", "/swipe", "/key", "/input", "/find", "/macro",
        )) {
            assertTrue(joined.contains(verb), "missing $verb in KNOWN_COMMANDS")
        }
    }
}
