package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class S3ServiceTest {

    private fun svc(
        url: String = "s3://examplebucket/a8s",
        options: S3Service.Options = S3Service.Options(),
    ) = S3Service(
        id = "bucket",
        s3Url = url,
        creds = SigV4.Credentials(
            "AKIAIOSFODNN7EXAMPLE",
            "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
        ),
        options = options.copy(nowMillis = { 1369353600000L }), // 2013-05-24T00:00:00Z
    )

    @Test
    fun `s3 url splits into bucket and prefix`() {
        assertEquals("b" to "a8s/files", S3Service.splitBucketUrl("s3://b/a8s/files"))
        assertEquals("b" to "", S3Service.splitBucketUrl("s3://b"))
        assertEquals("" to "", S3Service.splitBucketUrl("https://b/x"))
    }

    @Test
    fun `a bucketless url is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            svc(url = "s3://")
        }
    }

    @Test
    fun `blank credentials are refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            S3Service("x", "s3://b", SigV4.Credentials("", "s"))
        }
    }

    @Test
    fun `presign hours are bounded by the sigv4 ceiling`() {
        assertThrows(IllegalArgumentException::class.java) {
            svc(options = S3Service.Options(presignHours = 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            svc(options = S3Service.Options(presignHours = 169))
        }
    }

    @Test
    fun `aws addressing is virtual host style`() {
        val s = svc()
        assertEquals("examplebucket.s3.us-east-1.amazonaws.com", s.host())
        assertEquals("/a8s/t/f.png", s.requestPath("a8s/t/f.png"))
    }

    @Test
    fun `a custom endpoint addresses path style`() {
        val s = svc(options = S3Service.Options(endpointUrl = "https://objects.example.com:9000"))
        assertEquals("objects.example.com:9000", s.host())
        assertEquals("/examplebucket/a8s/t/f.png", s.requestPath("a8s/t/f.png"))
    }

    @Test
    fun `a plaintext endpoint is refused`() {
        assertThrows(IllegalArgumentException::class.java) {
            svc(options = S3Service.Options(endpointUrl = "http://objects.example.com"))
        }
    }

    @Test
    fun `store returns a presigned url any receiver can get`() {
        assertTrue(svc().producesPublicUrl)
        assertTrue(svc().preference == PREFERENCE_OWN_STORE)
    }

    @Test
    fun `presigned url carries the bucket host and expiry`() {
        val url = svc().presignedGet("a8s/deadbeef/test.txt")
        assertTrue(url.startsWith("https://examplebucket.s3.us-east-1.amazonaws.com/a8s/deadbeef/test.txt?"))
        assertTrue(url.contains("X-Amz-Expires=86400"))
        assertTrue(url.contains("X-Amz-Signature="))
    }

    @Test
    fun `own urls map back to object keys`() {
        val s = svc()
        assertEquals("a8s/t/f.png", s.keyFromUrl("s3://examplebucket/a8s/t/f.png"))
        assertEquals(
            "a8s/t/f.png",
            s.keyFromUrl("https://examplebucket.s3.us-east-1.amazonaws.com/a8s/t/f.png?X-Amz-Signature=x"),
        )
        assertEquals(
            "a8s/t/f.png",
            s.keyFromUrl("https://s3.us-east-1.amazonaws.com/examplebucket/a8s/t/f.png"),
        )
    }

    @Test
    fun `foreign urls are not claimed`() {
        val s = svc()
        assertNull(s.keyFromUrl("s3://other-bucket/a8s/f.png"))
        assertNull(s.keyFromUrl("https://other-bucket.s3.us-east-1.amazonaws.com/f.png"))
        assertNull(s.keyFromUrl("https://tempfile.org/abc/f.png"))
        assertNull(s.keyFromUrl("not a url"))
    }

    @Test
    fun `network parses an s3 service`() {
        val root = JSONObject(
            """{"services":{"bucket":{"service":"s3","url":"s3://examplebucket/a8s",
            "region":"us-west-2","access_key":"AKIAIOSFODNN7EXAMPLE",
            "secret_key":"x","presign_hours":48}}}""",
        )
        val services = Network.parseServices(root)
        assertEquals(1, services.size)
        val s = services[0] as S3Service
        assertEquals("bucket", s.id)
        assertEquals("examplebucket.s3.us-west-2.amazonaws.com", s.host())
    }

    @Test
    fun `network rejects an unknown s3 option`() {
        val root = JSONObject(
            """{"services":{"bucket":{"service":"s3","url":"s3://b",
            "access_key":"a","secret_key":"s","bogus":"1"}}}""",
        )
        assertThrows(IllegalArgumentException::class.java) {
            Network.parseServices(root)
        }
    }
}
