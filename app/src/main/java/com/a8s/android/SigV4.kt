package com.a8s.android

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * AWS Signature Version 4, the subset S3 needs: a presigned GET URL and a
 * header-signed PUT. Pure Kotlin + JCA — no AWS SDK.
 *
 * Mirrors what `apps/a8s/services/s3.py` gets from botocore upstream. On a
 * phone there is no credential chain — IAM roles do not exist — so the keys
 * live in `a8s.json` beside the MQTT and WebDAV secrets already there.
 */
object SigV4 {

    const val ALGORITHM: String = "AWS4-HMAC-SHA256"
    const val SERVICE: String = "s3"
    const val UNSIGNED_PAYLOAD: String = "UNSIGNED-PAYLOAD"

    /** The credential triple. [sessionToken] is set only for temporary
     *  credentials (STS), where it joins both signature inputs. */
    data class Credentials(
        val accessKey: String,
        val secretKey: String,
        val sessionToken: String? = null,
    )

    /** Where and when the signature is scoped — the pieces that make up the
     *  credential scope and the timestamp headers. */
    data class Context(val region: String, val amzDate: String, val dateStamp: String) {
        val scope: String get() = "$dateStamp/$region/$SERVICE/aws4_request"
    }

    /** The request's address: host goes in the signature's signed headers,
     *  path in the canonical URI. */
    data class Target(val host: String, val path: String)

    fun sha256Hex(data: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(data).toHex()

    private fun hmac(key: ByteArray, data: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data.toByteArray(Charsets.UTF_8))
        }

    /** The four-step key derivation: date, region, service, terminator. */
    fun signingKey(secret: String, dateStamp: String, region: String): ByteArray {
        var k = hmac("AWS4$secret".toByteArray(Charsets.UTF_8), dateStamp)
        k = hmac(k, region)
        k = hmac(k, SERVICE)
        k = hmac(k, "aws4_request")
        return k
    }

    /** RFC 3986 unreserved characters pass through; everything else is
     *  percent-encoded as uppercase UTF-8 bytes. [encodeSlash] keeps `/` for
     *  canonical paths and drops it for query values. */
    fun uriEncode(value: String, encodeSlash: Boolean): String {
        val out = StringBuilder()
        for (b in value.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt().toChar()
            val unreserved = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' ||
                c == '-' || c == '_' || c == '.' || c == '~'
            if (unreserved || (c == '/' && !encodeSlash)) {
                out.append(c)
            } else {
                out.append('%').append("%02X".format(b))
            }
        }
        return out.toString()
    }

    /**
     * Presigned GET URL for `https://host<path>`. The signature covers the
     * host header only, so the receiver fetches a bare URL with no signing
     * of its own — the same contract the upstream boto3 presign produces.
     */
    fun presignGet(
        target: Target,
        creds: Credentials,
        ctx: Context,
        expiresSeconds: Long,
    ): String {
        val host = target.host
        val params = sortedMapOf(
            "X-Amz-Algorithm" to ALGORITHM,
            "X-Amz-Credential" to "${creds.accessKey}/${ctx.scope}",
            "X-Amz-Date" to ctx.amzDate,
            "X-Amz-Expires" to expiresSeconds.toString(),
            "X-Amz-SignedHeaders" to "host",
        )
        creds.sessionToken?.let { params["X-Amz-Security-Token"] = it }
        val query = canonicalQuery(params)
        val canonicalPath = uriEncode(target.path, encodeSlash = false)
        val canonicalRequest = "GET\n$canonicalPath\n$query\n" +
            "host:$host\n\nhost\n$UNSIGNED_PAYLOAD"
        val signature = signature(creds.secretKey, ctx, canonicalRequest)
        return "https://$host$canonicalPath?$query&X-Amz-Signature=$signature"
    }

    /**
     * Headers for a request that carries its signature in-band — the PUT
     * upload — including the `authorization` entry itself. The caller sends
     * every header verbatim; they are all part of the signature. (`host` is
     * implied by the URL and restricted on HttpURLConnection, so it is in the
     * map for completeness but must not be set explicitly.)
     */
    fun signedHeaders(
        method: String,
        target: Target,
        creds: Credentials,
        ctx: Context,
        payloadSha256: String,
    ): Map<String, String> {
        val headers = sortedMapOf(
            "host" to target.host,
            "x-amz-content-sha256" to payloadSha256,
            "x-amz-date" to ctx.amzDate,
        )
        creds.sessionToken?.let { headers["x-amz-security-token"] = it }
        val canonicalHeaders = headers.entries.joinToString("") { "${it.key}:${it.value}\n" }
        val signedNames = headers.keys.joinToString(";")
        val canonicalPath = uriEncode(target.path, encodeSlash = false)
        val canonicalRequest = "$method\n$canonicalPath\n\n" +
            "$canonicalHeaders\n$signedNames\n$payloadSha256"
        val signature = signature(creds.secretKey, ctx, canonicalRequest)
        val auth = "$ALGORITHM Credential=${creds.accessKey}/${ctx.scope}, " +
            "SignedHeaders=$signedNames, Signature=$signature"
        return headers + mapOf("authorization" to auth)
    }

    private fun signature(secret: String, ctx: Context, canonicalRequest: String): String {
        val stringToSign = "$ALGORITHM\n${ctx.amzDate}\n${ctx.scope}\n" +
            sha256Hex(canonicalRequest.toByteArray(Charsets.UTF_8))
        return hmac(signingKey(secret, ctx.dateStamp, ctx.region), stringToSign).toHex()
    }

    private fun canonicalQuery(params: Map<String, String>): String =
        params.entries.joinToString("&") {
            "${uriEncode(it.key, encodeSlash = true)}=${uriEncode(it.value, encodeSlash = true)}"
        }

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
