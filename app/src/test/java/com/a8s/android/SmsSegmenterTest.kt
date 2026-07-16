package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsSegmenterTest {
    @Test
    fun `short GSM text remains one carrier unit`() {
        assertEquals(listOf("hello"), SmsSegmenter.split("hello") { it.length <= 160 })
    }

    @Test
    fun `long text is prefixed and every unit fits platform limit`() {
        val result = SmsSegmenter.split("word ".repeat(100)) { it.length <= 70 }
        assertTrue(result.size > 1)
        result.forEachIndexed { index, part ->
            assertTrue(part.startsWith("[${index + 1}/${result.size}] "))
            assertTrue(part.length <= 70)
        }
    }

    @Test
    fun `Unicode proxy limit and emoji preserve surrogate pairs`() {
        val result = SmsSegmenter.split("🙂".repeat(100)) { it.codePointCount(0, it.length) <= 40 }
        assertTrue(result.size > 1)
        result.forEach { part ->
            assertTrue(part.codePointCount(0, part.length) <= 40)
            assertTrue(!part.last().isHighSurrogate())
        }
    }

    @Test
    fun `word and URL stay intact when they fit`() {
        val url = "https://example.test/a/long/path"
        val result = SmsSegmenter.split("before words $url after words ".repeat(4)) { it.length <= 80 }
        assertTrue(result.none { it.endsWith("https://") })
        assertEquals(4, result.sumOf { it.split(url).size - 1 })
    }
}
