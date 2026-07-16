package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MqttPublishDiagnosticsTest {

    @Test
    fun `metadata extracts correlation fields without retaining content`() {
        val payload = """
            {
              "id":"01KTVEBX9HA8ZHMH5BP2EA38WQ",
              "from":"operator-phone",
              "to":"claude-code",
              "content":"private message text",
              "files":[]
            }
        """.trimIndent().toByteArray()

        val meta = MqttPublishDiagnostics.metadata(payload)

        assertEquals("01KTVEBX9HA8ZHMH5BP2EA38WQ", meta.envelopeId)
        assertEquals("operator-phone", meta.from)
        assertEquals("claude-code", meta.to)
        assertEquals(20, meta.contentLength)
    }

    @Test
    fun `malformed payload returns empty metadata`() {
        assertEquals(PublishMetadata("", "", "", 0), MqttPublishDiagnostics.metadata("nope".toByteArray()))
    }
}
