package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqttRouteTest {

    private fun config(
        device: String = "my-phone",
        phonebook: Map<String, String> = mapOf("Clover" to "+15550001111"),
    ): A8sAndroid.Config = A8sAndroid.Config(
        device = device,
        phonebook = phonebook,
        remotes = mapOf(
            "default" to RemoteConfig(
                broker = "ssl://broker:8883",
                topic = "t",
                username = "u",
                password = "p",
            ),
        ),
        services = emptyList(),
    )

    @Test
    fun `to == device with known sender and no slash returns NotACommand`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.NotACommand("Clover"), r)
    }

    @Test
    fun `to == device from unknown sender drops`() {
        val payload = """{"to":"my-phone","from":"stranger","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
    }

    @Test
    fun `to == device with empty from drops`() {
        val payload = """{"to":"my-phone","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
    }

    @Test
    fun `self-loopback (from equals device) is dropped`() {
        val payload = """{"to":"Clover","from":"my-phone","content":"hi"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("self-loopback"))
    }

    @Test
    fun `to not device drops`() {
        val payload = """{"to":"other-agent","from":"Clover","content":"yo"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not this device"))
    }

    @Test
    fun `missing to drops`() {
        val payload = """{"from":"Clover","content":"y"}"""
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
    fun `to == device with multiple phonebook entries returns NotACommand`() {
        val cfg = config(phonebook = mapOf("Alice" to "+1999", "Clover" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Clover","content":"hi"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.NotACommand("Clover"), r)
    }

    @Test
    fun `to non-device drops even if content has body field`() {
        val payload = """{"to":"Clover","from":"gerry","content":"present","body":"WRONG"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `whitespace-only content from known sender yields NotACommand`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"   "}"""
        val route = decideRoute(payload, config())
        assertEquals(MqttRoute.NotACommand("Clover"), route)
    }

    // ---------- /command routing (phonebook is the auth gate) ----------

    @Test
    fun `slash command from phonebook sender produces Command route`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/info"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Command("Alice", "info", emptyList()), r)
    }

    @Test
    fun `slash command parses args`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/logs 100"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Command("Alice", "logs", listOf("100")), r)
    }

    @Test
    fun `slash command from unknown sender drops`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"stranger","content":"/info"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
    }

    @Test
    fun `non-slash content from phonebook sender returns NotACommand`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550009999"))
        val payload = """{"to":"my-phone","from":"Alice","content":"hello"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.NotACommand("Alice"), r)
    }

    @Test
    fun `slash content yields Command not NotACommand`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/send +15559990000 hi there"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Command)
        assertEquals("send", (r as MqttRoute.Command).name)
        assertEquals(listOf("+15559990000", "hi", "there"), r.args)
    }

    @Test
    fun `command verb is lowercased`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/INFO"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Command("Alice", "info", emptyList()), r)
    }

    @Test
    fun `empty slash command drops`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/   "}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("empty command"))
    }

    // ---------- files parsing (parseEnvelopeFiles utility) ----------

    @Test
    fun `parseEnvelopeFiles extracts storage URLs`() {
        val json = org.json.JSONObject(
            """{"files":[{"filename":"photo.jpg","storage":["https://tempfile.org/abc/"]}]}""",
        )
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("photo.jpg", files[0].filename)
        assertEquals(listOf("https://tempfile.org/abc/"), files[0].storageUrls)
    }

    @Test
    fun `message to non-device with files still drops`() {
        val payload =
            """{"to":"Clover","from":"gerry","content":"here",""" +
                """"files":[{"filename":"doc.pdf","storage":["https://tempfile.org/x/"]}]}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `parseEnvelopeFiles handles missing storage array`() {
        val json = org.json.JSONObject(
            """{"files":[{"filename":"local.txt","path":"./.files/local.txt"}]}""",
        )
        val files = parseEnvelopeFiles(json)
        assertEquals(1, files.size)
        assertEquals("local.txt", files[0].filename)
        assertTrue(files[0].storageUrls.isEmpty())
    }

    @Test
    fun `parseEnvelopeFiles returns empty for no files array`() {
        val json = org.json.JSONObject("""{"content":"plain"}""")
        val files = parseEnvelopeFiles(json)
        assertTrue(files.isEmpty())
    }

    @Test
    fun `parseEnvelopeFiles preserves multiple storage URLs`() {
        val json = org.json.JSONObject(
            """{"files":[{"filename":"a.png","storage":["https://s1.org/1/","https://s2.org/2/"]}]}""",
        )
        val files = parseEnvelopeFiles(json)
        assertEquals(2, files[0].storageUrls.size)
    }

    @Test
    fun `command with files passes them through`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/send +15559990000 check this",""" +
            """"files":[{"filename":"photo.jpg","storage":["https://tempfile.org/abc123/"]}]}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Command)
        val cmd = r as MqttRoute.Command
        assertEquals("send", cmd.name)
        assertEquals(1, cmd.files.size)
        assertEquals("photo.jpg", cmd.files[0].filename)
        assertEquals(listOf("https://tempfile.org/abc123/"), cmd.files[0].storageUrls)
    }

    @Test
    fun `command without files has empty files list`() {
        val cfg = config(phonebook = mapOf("Alice" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Alice","content":"/info"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Command)
        assertTrue((r as MqttRoute.Command).files.isEmpty())
    }
}
