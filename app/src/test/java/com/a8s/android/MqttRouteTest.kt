package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqttRouteTest {

    private fun config(
        device: String = "android-pixel-7",
        rolesJson: String = """{"owner":{"commands":["*"]}}""",
        principalsJson: String = """
            [
              {"agent":"alice","roles":["owner"]},
              {"agent":"operator-phone","phone":"+15551234567","roles":["owner"]}
            ]
        """.trimIndent(),
    ): A8sAndroid.Config = TestFixtures.config(device = device, rolesJson = rolesJson, principalsJson = principalsJson)

    @Test
    fun `to == device with known sender and no slash returns NotACommand`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.NotACommand("alice"), r)
    }

    @Test
    fun `to == device from unknown sender drops`() {
        val payload = """{"to":"android-pixel-7","from":"stranger","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not a configured agent"))
    }

    @Test
    fun `to == device with empty from drops`() {
        val payload = """{"to":"android-pixel-7","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not a configured agent"))
    }

    @Test
    fun `self-loopback from device is dropped`() {
        val payload = """{"to":"android-pixel-7","from":"android-pixel-7","content":"/info"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("self-loopback"))
    }

    @Test
    fun `self-loopback from local agent is dropped`() {
        val payload = """{"to":"android-pixel-7","from":"operator-phone","content":"/info"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("self-loopback"))
    }

    @Test
    fun `isSelfOrigin matches device and phone agents but not remote agents`() {
        val cfg = config()
        assertTrue(isSelfOrigin("android-pixel-7", cfg))
        assertTrue(isSelfOrigin("operator-phone", cfg))
        assertFalse(isSelfOrigin("alice", cfg))
        assertFalse(isSelfOrigin("", cfg))
    }

    @Test
    fun `parseSlashTokens splits verb and args`() {
        val parsed = parseSlashTokens("/Tell Bob hi there")
        assertEquals("tell", parsed!!.first)
        assertEquals(listOf("Bob", "hi", "there"), parsed.second)
        assertNull(parseSlashTokens("/   "))
    }

    @Test
    fun `to not device drops`() {
        val payload = """{"to":"operator-phone","from":"alice","content":"/logs"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not this device"))
    }

    @Test
    fun `missing to drops`() {
        val payload = """{"from":"alice","content":"y"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("missing 'to'"))
    }

    @Test
    fun `malformed JSON yields ParseError`() {
        val r = decideRoute("not json", config())
        assertTrue(r is MqttRoute.ParseError)
    }

    @Test
    fun `to non-device drops even if content has body field`() {
        val payload = """{"to":"operator-phone","from":"alice","content":"present","body":"WRONG"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `whitespace-only content from known sender yields NotACommand`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"   "}"""
        val route = decideRoute(payload, config())
        assertEquals(MqttRoute.NotACommand("alice"), route)
    }

    @Test
    fun `slash command from authorized agent produces Command route`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/info"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Command("alice", "info", emptyList()), r)
    }

    @Test
    fun `slash command parses args`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/logs 100"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Command("alice", "logs", listOf("100")), r)
    }

    @Test
    fun `slash command from unknown sender drops`() {
        val payload = """{"to":"android-pixel-7","from":"stranger","content":"/info"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `slash command without role permission drops`() {
        val cfg = config(
            rolesJson = """{"owner":{"commands":["*"]},"viewer":{"commands":["info"]}}""",
            principalsJson = """
                [
                  {"agent":"alice","roles":["viewer"]},
                  {"agent":"operator-phone","phone":"+15551234567","roles":["owner"]}
                ]
            """.trimIndent(),
        )
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/logs"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not permitted"))
    }

    @Test
    fun `non-slash content from agent returns NotACommand`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.NotACommand("alice"), r)
    }

    @Test
    fun `slash content yields Command not NotACommand`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/send +15559990000 hi there"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Command)
        assertEquals("send", (r as MqttRoute.Command).name)
        assertEquals(listOf("+15559990000", "hi", "there"), r.args)
    }

    @Test
    fun `command verb is lowercased`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/INFO"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Command("alice", "info", emptyList()), r)
    }

    @Test
    fun `empty slash command drops`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/   "}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("empty command"))
    }

    @Test
    fun `parseEnvelopeFiles extracts storage URLs`() {
        val json = JSONObject(
            """{"files":[{"filename":"photo.jpg","storage":["https://tempfile.org/abc/"]}]}""",
        )
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("photo.jpg", files[0].filename)
        assertEquals(listOf("https://tempfile.org/abc/"), files[0].storageUrls)
    }

    @Test
    fun `message to phone agent with slash content drops at decideRoute`() {
        val payload = """{"to":"operator-phone","from":"alice","content":"/logs"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `parseEnvelopeFiles handles missing storage array`() {
        val json = JSONObject(
            """{"files":[{"filename":"local.txt","path":"./.files/local.txt"}]}""",
        )
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("local.txt", files[0].filename)
        assertTrue(files[0].storageUrls.isEmpty())
    }

    @Test
    fun `parseEnvelopeFiles returns empty for no files array`() {
        val json = JSONObject("""{"content":"plain"}""")
        val files = parseEnvelopeFiles(json)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `command with files passes them through`() {
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/send +15559990000 check this",""" +
            """"files":[{"filename":"photo.jpg","storage":["https://tempfile.org/abc123/"]}]}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Command)
        val cmd = r as MqttRoute.Command
        assertEquals("send", cmd.name)
        assertEquals(1, cmd.files.size)
        assertEquals("photo.jpg", cmd.files[0].filename)
    }

    @Test
    fun `command carries envelope id from json`() {
        val payload =
            """{"id":"01KTVEBX9HA8ZHMH5BP2EA38WQ","to":"android-pixel-7","from":"alice",""" +
                """"content":"/send +15559990000 hi"}"""
        val r = decideRoute(payload, config()) as MqttRoute.Command
        assertEquals("01KTVEBX9HA8ZHMH5BP2EA38WQ", r.envelopeId)
    }

    @Test
    fun `NotACommand does not carry files`() {
        val payload =
            """{"to":"android-pixel-7","from":"alice","content":"hello",""" +
                """"files":[{"filename":"x.jpg","storage":["https://tempfile.org/x/"]}]}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.NotACommand)
    }
}
