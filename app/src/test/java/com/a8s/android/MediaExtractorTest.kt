package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaExtractorTest {
    @Test fun `jpeg maps to jpg`() = assertEquals("jpg", MediaExtractor.mimeTypeToExtension("image/jpeg"))
    @Test fun `png maps to png`() = assertEquals("png", MediaExtractor.mimeTypeToExtension("image/png"))
    @Test fun `gif maps to gif`() = assertEquals("gif", MediaExtractor.mimeTypeToExtension("image/gif"))
    @Test fun `webp maps to webp`() = assertEquals("webp", MediaExtractor.mimeTypeToExtension("image/webp"))
    @Test fun `video mp4 maps to mp4`() = assertEquals("mp4", MediaExtractor.mimeTypeToExtension("video/mp4"))
    @Test fun `video webm falls back to mp4`() = assertEquals("mp4", MediaExtractor.mimeTypeToExtension("video/webm"))
    @Test fun `audio mpeg falls back to m4a`() = assertEquals("m4a", MediaExtractor.mimeTypeToExtension("audio/mpeg"))
    @Test fun `unknown type falls back to bin`() = assertEquals("bin", MediaExtractor.mimeTypeToExtension("application/pdf"))
    @Test fun `empty string falls back to bin`() = assertEquals("bin", MediaExtractor.mimeTypeToExtension(""))
}
