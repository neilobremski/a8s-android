package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsSegmenterTest {
    @Test
    fun `short text remains one unprefixed logical message`() {
        assertEquals(listOf("hello"), SmsSegmenter.split("hello", 1000, 123))
    }

    @Test
    fun `long text has readable message and part headers`() {
        val result = SmsSegmenter.split("word ".repeat(100), 120, 123)

        assertTrue(result.size > 1)
        result.forEachIndexed { index, part ->
            assertTrue(part.startsWith("Message 123 part ${index + 1} of ${result.size}: "))
            assertTrue(part.length <= 120)
        }
    }

    @Test
    fun `Unicode boundaries preserve surrogate pairs`() {
        val result = SmsSegmenter.split(("words 🙂 ").repeat(40), 100, 7)

        assertTrue(result.size > 1)
        result.forEach { part ->
            assertTrue(!part.last().isHighSurrogate())
            assertTrue(part.length <= 100)
        }
    }

    @Test
    fun `words and URLs stay intact`() {
        val url = "https://example.test/a/long/path?with=query&and=value"
        val body = "before words $url after words ".repeat(6)
        val result = SmsSegmenter.split(body, 140, 88)

        assertEquals(6, result.sumOf { it.split(url).size - 1 })
        assertTrue(result.none { it.endsWith("https://") })
        assertTrue(result.none { it.endsWith("example.test") })
    }

    @Test
    fun `URL longer than chunk budget is kept whole`() {
        val url = "https://example.test/" + "path".repeat(40)
        val result = SmsSegmenter.split("open $url when ready", 100, 9)

        assertEquals(1, result.sumOf { it.split(url).size - 1 })
        assertTrue(result.any { it.length > 100 })
    }

    @Test
    fun `single oversized URL remains one unprefixed message`() {
        val url = "https://example.test/" + "path".repeat(40)

        assertEquals(listOf(url), SmsSegmenter.split(url, 100, 9))
    }
}
