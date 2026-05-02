package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CommandsTest {

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
