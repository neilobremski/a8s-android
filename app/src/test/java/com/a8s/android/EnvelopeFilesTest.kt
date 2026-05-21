package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EnvelopeFilesTest {

    @Test
    fun `parses storage URLs from files array`() {
        val json = JSONObject("""{"files":[{"filename":"photo.jpg","storage":["https://tempfile.org/abc/"]}]}""")
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("photo.jpg", files[0].filename)
        assertEquals(listOf("https://tempfile.org/abc/"), files[0].storageUrls)
    }

    @Test
    fun `handles multiple files`() {
        val json = JSONObject("""{"files":[{"filename":"a.jpg","storage":["https://s.org/1/"]},{"filename":"b.pdf","storage":["https://s.org/2/","https://s2.org/3/"]}]}""")
        val files = parseEnvelopeFiles(json)
        assertEquals(2, files.size)
        assertEquals("b.pdf", files[1].filename)
        assertEquals(2, files[1].storageUrls.size)
    }

    @Test
    fun `file without storage array has empty URLs`() {
        val json = JSONObject("""{"files":[{"filename":"local.txt","path":"./.files/local.txt"}]}""")
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertTrue(files[0].storageUrls.isEmpty())
    }

    @Test
    fun `missing files key yields empty list`() {
        val json = JSONObject("""{"content":"hello"}""")
        assertTrue(parseEnvelopeFiles(json).isEmpty())
    }

    @Test
    fun `empty files array yields empty list`() {
        val json = JSONObject("""{"files":[]}""")
        assertTrue(parseEnvelopeFiles(json).isEmpty())
    }

    @Test
    fun `skips entries with blank filename`() {
        val json = JSONObject("""{"files":[{"filename":"","storage":["https://s.org/1/"]},{"filename":"good.txt","storage":["https://s.org/2/"]}]}""")
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("good.txt", files[0].filename)
    }

    @Test
    fun `skips blank URLs in storage array`() {
        val json = JSONObject("""{"files":[{"filename":"x.png","storage":["","https://s.org/1/",""]}]}""")
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files[0].storageUrls.size)
        assertEquals("https://s.org/1/", files[0].storageUrls[0])
    }
}
