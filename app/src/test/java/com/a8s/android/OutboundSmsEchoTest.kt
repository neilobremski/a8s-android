package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class OutboundSmsEchoTest {

    @Test
    fun `records and matches multipart segment from same number`() {
        val echo = OutboundSmsEcho(windowMs = 60_000L)
        echo.recordParts("+15551234567", listOf("tell alice -> bob: hello", "world tail"), now = 1000L)
        assertTrue(echo.isEcho("+15551234567", "world tail", now = 2000L))
        assertFalse(echo.isEcho("+15551234567", "unrelated", now = 2000L))
    }

    @Test
    fun `echo expires after window`() {
        val echo = OutboundSmsEcho(windowMs = 1000L)
        echo.recordParts("+15551234567", listOf("fragment"), now = 0L)
        assertFalse(echo.isEcho("+15551234567", "fragment", now = 2000L))
    }

    @Test
    fun `digit formatting differences still match`() {
        val echo = OutboundSmsEcho(windowMs = 60_000L)
        echo.recordParts("+1 555-123-4567", listOf("icy of your sandbox app"), now = 1000L)
        assertTrue(echo.isEcho("5551234567", "icy of your sandbox app", now = 2000L))
    }
}
