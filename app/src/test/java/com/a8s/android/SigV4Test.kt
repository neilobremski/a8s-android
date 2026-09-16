package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Expected values come from the AWS-documented SigV4 signing process —
 * same credentials and timestamp as the published example, reproduced by an
 * independent reference implementation.
 */
class SigV4Test {

    private val creds = SigV4.Credentials(
        accessKey = "AKIAIOSFODNN7EXAMPLE",
        secretKey = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY",
    )
    private val ctx = SigV4.Context(
        region = "us-east-1",
        amzDate = "20130524T000000Z",
        dateStamp = "20130524",
    )
    private val target = SigV4.Target("examplebucket.s3.us-east-1.amazonaws.com", "/test.txt")

    @Test
    fun `presigned get matches the reference signature`() {
        val url = SigV4.presignGet(target, creds, ctx, expiresSeconds = 86400)
        assertEquals(
            "https://examplebucket.s3.us-east-1.amazonaws.com/test.txt" +
                "?X-Amz-Algorithm=AWS4-HMAC-SHA256" +
                "&X-Amz-Credential=AKIAIOSFODNN7EXAMPLE%2F20130524%2Fus-east-1%2Fs3%2Faws4_request" +
                "&X-Amz-Date=20130524T000000Z&X-Amz-Expires=86400&X-Amz-SignedHeaders=host" +
                "&X-Amz-Signature=762f4fcbacec730d460b0e337f554e569e4fe98643baefad7af1276fe3084e7f",
            url,
        )
    }

    @Test
    fun `header-signed put matches the reference signature`() {
        val headers = SigV4.signedHeaders(
            "PUT", target, creds, ctx,
            payloadSha256 = "7bb6f9f7a47a63e684925af3608c059edcc371eb81188c48c9714896fb1091fd",
        )
        assertEquals(
            "AWS4-HMAC-SHA256 " +
                "Credential=AKIAIOSFODNN7EXAMPLE/20130524/us-east-1/s3/aws4_request, " +
                "SignedHeaders=host;x-amz-content-sha256;x-amz-date, " +
                "Signature=56eb44eb7a52d10a78f9f3bcc281bedc40651318dc907b41dc7db1e238a297f5",
            headers["authorization"],
        )
    }

    @Test
    fun `a session token joins the signed headers and the presign query`() {
        val temp = creds.copy(sessionToken = "token123")
        val short = SigV4.Target(target.host, "/k")
        val headers = SigV4.signedHeaders("PUT", short, temp, ctx, "0".repeat(64))
        assertEquals("token123", headers["x-amz-security-token"])
        val url = SigV4.presignGet(short, temp, ctx, 3600)
        assertEquals(true, url.contains("X-Amz-Security-Token=token123"))
    }

    @Test
    fun `uri encoding keeps unreserved characters and encodes the rest`() {
        assertEquals("a-zA-Z0-9-._~", SigV4.uriEncode("a-zA-Z0-9-._~", encodeSlash = true))
        assertEquals("a%20b", SigV4.uriEncode("a b", encodeSlash = true))
        assertEquals("a%2Fb", SigV4.uriEncode("a/b", encodeSlash = true))
        assertEquals("a/b", SigV4.uriEncode("a/b", encodeSlash = false))
        assertEquals("%E2%82%AC", SigV4.uriEncode("€", encodeSlash = true))
    }

    @Test
    fun `sha256 of the documented payload`() {
        assertEquals(
            "7bb6f9f7a47a63e684925af3608c059edcc371eb81188c48c9714896fb1091fd",
            SigV4.sha256Hex("file contents".toByteArray()),
        )
    }
}
