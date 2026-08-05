package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AttachmentPathTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `a plain filename resolves inside the directory`() {
        val r = AttachmentPath.bundleFile(tempDir, "memo.m4a")
        assertNotNull(r.file)
        assertEquals(tempDir.canonicalFile, r.file!!.parentFile)
        assertEquals("memo.m4a", r.file!!.name)
    }

    @Test
    fun `a traversing filename is refused`() {
        // `filename` arrives on the wire. Whoever publishes the envelope
        // chooses it, so it must not be able to select the write location.
        for (hostile in listOf(
            "../escaped.txt",
            "../../../../etc/cron.d/pwned",
            "sub/dir/file.txt",
            "..",
            ".",
        )) {
            val r = AttachmentPath.bundleFile(tempDir, hostile)
            assertNull(r.file, "expected '$hostile' to be refused")
            assert(r.reason.isNotEmpty())
        }
    }

    @Test
    fun `an absolute path is refused`() {
        val r = AttachmentPath.bundleFile(tempDir, "/etc/passwd")
        assertNull(r.file)
    }

    @Test
    fun `a blank filename is refused`() {
        assertNull(AttachmentPath.bundleFile(tempDir, "   ").file)
        assertNull(AttachmentPath.bundleFile(tempDir, "").file)
    }
}
