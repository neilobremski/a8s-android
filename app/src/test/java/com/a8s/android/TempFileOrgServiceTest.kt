package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class TempFileOrgServiceTest {

    @Test
    fun `parseUploadUrl extracts files-zero-url`() {
        // Real tempfile.org response shape — the URL lives at
        // `files[0].url`, not at the top level.
        val body = """{"files":[
          {"id":"abc","name":"x.png","size":1234,
           "url":"https://tempfile.org/abc/","expiryTime":"..."}
        ]}"""
        assertEquals("https://tempfile.org/abc/", TempFileOrgService.parseUploadUrl(body))
    }

    @Test
    fun `parseUploadUrl picks first when multiple files`() {
        val body = """{"files":[
          {"url":"https://tempfile.org/a/"},
          {"url":"https://tempfile.org/b/"}
        ]}"""
        assertEquals("https://tempfile.org/a/", TempFileOrgService.parseUploadUrl(body))
    }

    @Test
    fun `parseUploadUrl returns null when files array empty`() {
        assertNull(TempFileOrgService.parseUploadUrl("""{"files":[]}"""))
    }

    @Test
    fun `parseUploadUrl returns null when no files key`() {
        assertNull(TempFileOrgService.parseUploadUrl("""{"error":"bad request"}"""))
    }

    @Test
    fun `parseUploadUrl returns null on invalid JSON`() {
        assertNull(TempFileOrgService.parseUploadUrl("not json"))
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
