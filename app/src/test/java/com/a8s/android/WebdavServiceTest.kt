package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebdavServiceTest {

    private fun svc(baseUrl: String? = "https://files.example.com/a8s") = WebdavService(
        id = "fm",
        davUrl = "webdav://dav.example.com/dav/files/user/a8s",
        baseUrl = baseUrl,
        credentials = WebdavService.Credentials("user@example.com", "secret"),
    )

    @Test
    fun `webdav scheme maps to https for the wire`() {
        assertEquals(
            "https://dav.example.com/dav/a8s",
            WebdavService.toHttps("webdav://dav.example.com/dav/a8s"),
        )
        assertEquals(
            "https://dav.example.com/dav/a8s",
            WebdavService.toHttps("https://dav.example.com/dav/a8s"),
        )
    }

    @Test
    fun `plaintext and unknown schemes are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            WebdavService.toHttps("http://dav.example.com/dav")
        }
        assertThrows(IllegalArgumentException::class.java) {
            WebdavService.toHttps("dav.example.com/dav")
        }
    }

    @Test
    fun `a base url makes the upload publicly fetchable`() {
        assertTrue(svc().producesPublicUrl)
    }

    @Test
    fun `without a base url the upload is not publicly fetchable`() {
        // The WebDAV endpoint needs credentials, so a recipient cannot GET it.
        // This phone cannot send bytes, so such an upload delivers nothing.
        assertFalse(svc(baseUrl = null).producesPublicUrl)
    }

    @Test
    fun `webdav is preferred over a public paste host`() {
        val tempfile = TempFileOrgService("t", "https://tempfile.org")
        assertTrue(svc().preference < tempfile.preference)
    }

    @Test
    fun `only URLs under the base url map back to an object key`() {
        val s = svc()
        assertEquals("a8s/abc/memo.m4a", s.relativeKey("https://files.example.com/a8s/a8s/abc/memo.m4a"))
        assertEquals("a8s/abc/memo.m4a", s.relativeKey("https://files.example.com/a8s/a8s/abc/memo.m4a?x=1"))
        assertNull(s.relativeKey("https://other.example.com/a8s/abc/memo.m4a"))
        assertNull(s.relativeKey("https://files.example.com/a8s/"))
        assertNull(s.relativeKey("https://tempfile.org/abc/"))
    }

    @Test
    fun `a service with no base url claims nothing`() {
        assertNull(svc(baseUrl = null).relativeKey("https://files.example.com/a8s/x/y.txt"))
    }
}
