package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MediaExtractorTest {
    @Test fun `jpeg maps to jpg`() = assertEquals("jpg", MediaExtractor.mimeTypeToExtension("image/jpeg"))
    @Test fun `png maps to png`() = assertEquals("png", MediaExtractor.mimeTypeToExtension("image/png"))
    @Test fun `gif maps to gif`() = assertEquals("gif", MediaExtractor.mimeTypeToExtension("image/gif"))
    @Test fun `webp maps to webp`() = assertEquals("webp", MediaExtractor.mimeTypeToExtension("image/webp"))
    @Test fun `heic maps to heic`() = assertEquals("heic", MediaExtractor.mimeTypeToExtension("image/heic"))
    @Test fun `heif maps to heif`() = assertEquals("heif", MediaExtractor.mimeTypeToExtension("image/heif"))
    @Test fun `unknown image falls back to jpg`() = assertEquals("jpg", MediaExtractor.mimeTypeToExtension("image/bmp"))
    @Test fun `video mp4 maps to mp4`() = assertEquals("mp4", MediaExtractor.mimeTypeToExtension("video/mp4"))
    @Test fun `video webm falls back to mp4`() = assertEquals("mp4", MediaExtractor.mimeTypeToExtension("video/webm"))
    @Test fun `audio mpeg maps to mp3`() = assertEquals("mp3", MediaExtractor.mimeTypeToExtension("audio/mpeg"))
    @Test fun `audio amr maps to amr`() = assertEquals("amr", MediaExtractor.mimeTypeToExtension("audio/amr-wb"))
    @Test fun `audio wav maps to wav`() = assertEquals("wav", MediaExtractor.mimeTypeToExtension("audio/x-wav"))
    @Test fun `audio ogg maps to ogg`() = assertEquals("ogg", MediaExtractor.mimeTypeToExtension("audio/ogg"))
    @Test fun `unknown type falls back to bin`() = assertEquals("bin", MediaExtractor.mimeTypeToExtension("application/pdf"))
    @Test fun `empty string falls back to bin`() = assertEquals("bin", MediaExtractor.mimeTypeToExtension(""))
}
