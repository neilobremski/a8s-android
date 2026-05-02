package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TempFileOrgServiceTest {

    @Test
    fun `parseUploadUrl extracts JSON url field`() {
        val body = """{"id":"abc","url":"https://tempfile.org/d/abc","ok":true}"""
        assertEquals("https://tempfile.org/d/abc", TempFileOrgService.parseUploadUrl(body))
    }

    @Test
    fun `parseUploadUrl falls back to first URL in body`() {
        val body = """OK https://tempfile.org/d/foo successfully uploaded"""
        assertEquals("https://tempfile.org/d/foo", TempFileOrgService.parseUploadUrl(body))
    }

    @Test
    fun `parseUploadUrl returns null when no URL`() {
        assertNull(TempFileOrgService.parseUploadUrl("error: invalid request"))
    }

    @Test
    fun `parseUploadUrl handles single quotes`() {
        val body = """{'url': 'https://tempfile.org/d/x'}"""
        assertEquals("https://tempfile.org/d/x", TempFileOrgService.parseUploadUrl(body))
    }

    @Test
    fun `constructor accepts allowed expiry values`() {
        for (h in listOf(1, 6, 24, 48)) {
            val svc = TempFileOrgService("t", "https://tempfile.org", expiryHours = h)
            assertNotNull(svc)
        }
    }

    @Test
    fun `constructor rejects out-of-range expiry`() {
        assertThrows(IllegalArgumentException::class.java) {
            TempFileOrgService("t", "https://tempfile.org", expiryHours = 12)
        }
    }
}
