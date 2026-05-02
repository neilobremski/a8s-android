package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsTest {

    private val baseSnapshot = Commands.InfoSnapshot(
        appVersion = "v1.10.0 (build 11)",
        deviceModel = "Google Pixel 7",
        androidRelease = "14",
        sdkInt = 34,
        remotes = listOf(
            Commands.RemoteStatus("hivemq", "ssl://broker:8883", "neiltest", connected = true),
        ),
        services = listOf("tempfile"),
        networkType = "WIFI",
        batteryPercent = 87,
        batteryCharging = false,
        uptimeMs = 5 * 60 * 1000L,
        phonebookSize = 1,
        ownerSet = true,
        forwardSet = true,
    )

    @Test
    fun `info contains key fields`() {
        val out = Commands.renderInfo(baseSnapshot)
        assertTrue(out.contains("a8s-android v1.10.0"))
        assertTrue(out.contains("Google Pixel 7"))
        assertTrue(out.contains("API 34"))
        assertTrue(out.contains("Remotes: 1/1 connected"))
        assertTrue(out.contains("hivemq → ssl://broker:8883 / neiltest"))
        assertTrue(out.contains("Storage: tempfile"))
        assertTrue(out.contains("WIFI"))
        assertTrue(out.contains("87%"))
        assertTrue(out.contains("phonebook=1"))
    }

    @Test
    fun `info shows partial connection`() {
        val out = Commands.renderInfo(
            baseSnapshot.copy(
                remotes = listOf(
                    Commands.RemoteStatus("a", "ssl://a:8883", "t", connected = true),
                    Commands.RemoteStatus("b", "ssl://b:8883", "t", connected = false),
                ),
            ),
        )
        assertTrue(out.contains("Remotes: 1/2 connected"))
        assertTrue(out.contains("✓ a"))
        assertTrue(out.contains("✗ b"))
    }

    @Test
    fun `info reports zero remotes configured`() {
        val out = Commands.renderInfo(baseSnapshot.copy(remotes = emptyList()))
        assertTrue(out.contains("Remotes: (none configured)"))
    }

    @Test
    fun `info reports no storage when empty`() {
        val out = Commands.renderInfo(baseSnapshot.copy(services = emptyList()))
        assertTrue(out.contains("Storage: (none)"))
    }

    @Test
    fun `info shows charging`() {
        val out = Commands.renderInfo(baseSnapshot.copy(batteryCharging = true))
        assertTrue(out.contains("(charging)"))
    }

    @Test
    fun `info handles null battery`() {
        val out = Commands.renderInfo(baseSnapshot.copy(batteryPercent = null))
        assertTrue(out.contains("Battery: ?"))
    }

    @Test
    fun `info uptime formatting picks scale`() {
        assertTrue(Commands.renderInfo(baseSnapshot.copy(uptimeMs = 45 * 1000L)).contains("Uptime: 45s"))
        assertTrue(Commands.renderInfo(baseSnapshot.copy(uptimeMs = 5 * 60 * 1000L + 12 * 1000L))
            .contains("Uptime: 5m 12s"))
        assertTrue(Commands.renderInfo(baseSnapshot.copy(uptimeMs = 3 * 3600 * 1000L + 4 * 60 * 1000L))
            .contains("Uptime: 3h 4m"))
    }

    @Test
    fun `logs returns last N lines with header`() {
        val logs = (1..100).joinToString("\n") { "line $it" }
        val out = Commands.renderLogs(logs, 5)
        assertTrue(out.startsWith("logs: last 5 of 100 line(s)"))
        assertTrue(out.contains("line 96"))
        assertTrue(out.contains("line 100"))
        assertTrue(!out.contains("line 95"))
    }

    @Test
    fun `logs N is clamped`() {
        val logs = "a\nb\nc"
        // 0 clamps to 1
        assertTrue(Commands.renderLogs(logs, 0).endsWith("c"))
        // 9999 clamps to 500 — fewer than 500 actual lines, so all return
        val out = Commands.renderLogs(logs, 9999)
        assertTrue(out.contains("a") && out.contains("b") && out.contains("c"))
    }

    @Test
    fun `logs short input returns all lines`() {
        val logs = "x\ny"
        val out = Commands.renderLogs(logs, 50)
        assertTrue(out.contains("x"))
        assertTrue(out.contains("y"))
        assertTrue(out.contains("logs: last 2 of 2"))
    }

    @Test
    fun `parseLogsArgs default when empty`() {
        assertEquals(Commands.DEFAULT_LOGS_LINES, Commands.parseLogsArgs(emptyList()))
    }

    @Test
    fun `parseLogsArgs reads first integer`() {
        assertEquals(25, Commands.parseLogsArgs(listOf("25")))
    }

    @Test
    fun `parseLogsArgs falls back to default on garbage`() {
        assertEquals(Commands.DEFAULT_LOGS_LINES, Commands.parseLogsArgs(listOf("foo")))
    }

    @Test
    fun `unknown command lists known ones`() {
        val out = Commands.renderUnknown("bogus")
        assertTrue(out.contains("/bogus"))
        assertTrue(out.contains("/info"))
        assertTrue(out.contains("/logs"))
        assertTrue(out.contains("/update"))
        assertTrue(out.contains("/screenshot"))
        assertTrue(out.contains("/photo"))
        assertTrue(out.contains("/video"))
        assertTrue(out.contains("/location"))
        assertTrue(out.contains("/say"))
        assertTrue(out.contains("/notify"))
        assertTrue(out.contains("/ls"))
        assertTrue(out.contains("/cat"))
        assertTrue(out.contains("/rm"))
        assertTrue(out.contains("/tap"))
        assertTrue(out.contains("/longtap"))
        assertTrue(out.contains("/swipe"))
        assertTrue(out.contains("/key"))
        assertTrue(out.contains("/input"))
        assertTrue(out.contains("/find"))
        assertTrue(out.contains("/macro"))
    }
}
