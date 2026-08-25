package com.a8s.android

import org.json.JSONObject
import org.json.JSONArray
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The phone cannot send bytes, so an attachment reaches a recipient only as a
 * public URL. These tests pin the consequence: when no such URL exists, the
 * message says so instead of carrying a filename nobody can fetch.
 */
class AttachmentDeliveryTest {

    @Test
    fun `body carries the URL when one exists`() {
        val files = listOf(EnvelopeFile("memo.m4a", listOf("https://files.example.com/a8s/x/memo.m4a")))
        val body = CmdHelpers.buildSendBody("here it is", files)
        assertEquals("here it is\nhttps://files.example.com/a8s/x/memo.m4a", body)
    }

    @Test
    fun `body names the failure when no URL exists`() {
        val files = listOf(
            EnvelopeFile(
                "memo.m4a",
                emptyList(),
                error = ATTACHMENT_UNAVAILABLE,
                detail = "no storage service configured; cannot publish attachments",
            ),
        )
        val body = CmdHelpers.buildSendBody("here it is", files)
        assertTrue(body.startsWith("here it is\n"))
        assertTrue(body.contains("ATTACHMENT UNAVAILABLE: memo.m4a"))
        assertTrue(body.contains("no storage service configured"))
    }

    @Test
    fun `an errored entry never contributes a URL, even if one is present`() {
        // A credential-gated URL is not a delivery route for a recipient.
        val files = listOf(
            EnvelopeFile(
                "memo.m4a",
                listOf("https://dav.example.com/dav/a8s/x/memo.m4a"),
                error = ATTACHMENT_UNAVAILABLE,
                detail = "no configured storage service produces a public URL",
            ),
        )
        val body = CmdHelpers.buildSendBody("here it is", files)
        assertTrue(body.contains("ATTACHMENT UNAVAILABLE: memo.m4a"))
        assertTrue(!body.contains("dav.example.com"))
    }

    @Test
    fun `text alone is unchanged`() {
        assertEquals("just text", CmdHelpers.buildSendBody("just text", emptyList()))
    }

    @Test
    fun `an inbound envelope keeps the failure the sender reported`() {
        val json = JSONObject(
            """
            {"to":"android-pixel-7","from":"alice","content":"see attached",
             "files":[{"filename":"memo.m4a","error":"ATTACHMENT_UNAVAILABLE",
                       "detail":"could not download after 900s"}]}
            """.trimIndent(),
        )
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals(ATTACHMENT_UNAVAILABLE, files[0].error)
        assertEquals("could not download after 900s", files[0].detail)
        assertTrue(files[0].storageUrls.isEmpty())
    }

    @Test
    fun `an ordinary inbound entry has no error`() {
        val json = JSONObject(
            """
            {"to":"android-pixel-7","from":"alice","content":"see attached",
             "files":[{"filename":"a.jpg","storage":["https://tempfile.org/abc/"]}]}
            """.trimIndent(),
        )
        val files = parseEnvelopeFiles(json)
        assertNull(files[0].error)
        assertEquals(listOf("https://tempfile.org/abc/"), files[0].storageUrls)
    }

    @Test
    fun `upload failure alert is sent once with failed filenames`() {
        val files = JSONArray(
            """[
                {"filename":"memo.m4a","error":"ATTACHMENT_UNAVAILABLE"},
                {"filename":"photo.jpg","storage":["https://files.example.com/photo.jpg"]},
                {"filename":"notes.pdf","error":"ATTACHMENT_UNAVAILABLE"}
            ]""".trimIndent(),
        )

        val alert = AttachmentFailureAlert.build(files)!!
        assertTrue(alert.contains("memo.m4a, notes.pdf"))
        assertTrue(alert.contains("forwarded with an unavailable-attachment notice"))
        assertTrue(!alert.contains("photo.jpg"))
    }

    @Test
    fun `upload failure alert is absent when every attachment has a public URL`() {
        val files = JSONArray(
            """[{"filename":"photo.jpg","storage":["https://files.example.com/photo.jpg"]}]""",
        )

        assertNull(AttachmentFailureAlert.build(files))
    }
}
