package com.a8s.android

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
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
        options = WebdavService.Options(
            baseUrl = baseUrl,
            credentials = WebdavService.Credentials("user@example.com", "secret"),
        ),
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

    @Test
    fun `every ancestor collection is listed outermost first`() {
        assertEquals(
            listOf("a8s", "a8s/3fa9c2d1"),
            WebdavService.ancestorPaths("a8s/3fa9c2d1/photo.jpg"),
        )
    }

    @Test
    fun `a key with no directory needs no collections`() {
        assertEquals(emptyList<String>(), WebdavService.ancestorPaths("photo.jpg"))
    }

    @Test
    fun `stray slashes do not produce empty collections`() {
        assertEquals(
            listOf("a8s", "a8s/3fa9c2d1"),
            WebdavService.ancestorPaths("/a8s//3fa9c2d1/photo.jpg"),
        )
    }

    /** Real HTTPS server + real OkHttp client: asserts the MKCOL ancestors →
     *  PUT sequence that actually goes out on the wire, which is the part a
     *  pure-function test cannot see. */
    private fun <T> withDavServer(block: (MockWebServer, WebdavService) -> T): T {
        val cert = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val clientCerts = HandshakeCertificates.Builder()
            .addTrustedCertificate(cert.certificate)
            .build()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCerts.sslSocketFactory(), clientCerts.trustManager)
            .build()
        MockWebServer().use { server ->
            server.useHttps(
                HandshakeCertificates.Builder().heldCertificate(cert).build().sslSocketFactory(),
                false,
            )
            server.start()
            val svc = WebdavService(
                id = "dav",
                davUrl = "https://localhost:${server.port}/dav",
                options = WebdavService.Options(
                    baseUrl = "https://files.example.com/a8s",
                    http = client,
                ),
            )
            return block(server, svc)
        }
    }

    private fun mockAccepted(server: MockWebServer, count: Int) {
        repeat(count) { server.enqueue(MockResponse().setResponseCode(201)) }
    }

    @Test
    fun `store MKCOLs each ancestor then PUTs the bytes`() = withDavServer { server, svc ->
        mockAccepted(server, 3)
        val file = createTempFile(suffix = ".jpg").apply { writeBytes(ByteArray(512) { 7 }) }

        val url = svc.store(file)

        assertTrue(url.startsWith("https://files.example.com/a8s/a8s/"))
        val first = server.takeRequest(5, TimeUnit.SECONDS)!!
        val second = server.takeRequest(5, TimeUnit.SECONDS)!!
        val put = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("MKCOL", first.method)
        assertEquals("/dav/a8s", first.path)
        assertEquals("MKCOL", second.method)
        assertTrue(second.path!!.startsWith("/dav/a8s/"))
        assertEquals("PUT", put.method)
        assertTrue(put.path!!.endsWith("/" + file.name))
        assertTrue(put.body.readByteArray().contentEquals(ByteArray(512) { 7 }))
    }

    @Test
    fun `store failure surfaces as a StorageException`() = withDavServer { server, svc ->
        mockAccepted(server, 2)
        server.enqueue(MockResponse().setResponseCode(500))
        val file = createTempFile().apply { writeText("x") }
        assertThrows(StorageException::class.java) { svc.store(file) }
    }

    @Test
    fun `retrieve writes the response body to dest`() = withDavServer { server, svc ->
        val bytes = ByteArray(1024) { it.toByte() }
        server.enqueue(MockResponse().setResponseCode(200).setBody(okio.Buffer().write(bytes)))
        val dest = createTempFile().apply { delete() }
        assertTrue(svc.retrieve("https://files.example.com/a8s/x/y.bin", dest))

        val get = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("GET", get.method)
        assertEquals("/dav/x/y.bin", get.path)
        assertTrue(dest.readBytes().contentEquals(bytes))
    }
}
