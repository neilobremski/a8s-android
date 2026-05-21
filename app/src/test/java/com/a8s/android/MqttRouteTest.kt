package com.a8s.android

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
    fun `to == device with known sender forwards to that sender's number`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Forward("+15550001111", "hello"), r)
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
    fun `to in phonebook produces Phonebook route`() {
        val payload = """{"to":"Clover","from":"gerry","content":"yo"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Phonebook("Clover", "+15550001111", "yo"), r)
    }

    @Test
    fun `to not in phonebook and not device drops`() {
        val payload = """{"to":"unknown","from":"gerry","content":"x"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
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
    fun `device wins over phonebook entry of the same name`() {
        val cfg = config(phonebook = mapOf("my-phone" to "+1999", "Clover" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Clover","content":"hi"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Forward)
        assertEquals(MqttRoute.Forward("+15550001111", "hi"), r)
    }

    @Test
    fun `content field is read instead of body`() {
        val payload = """{"to":"Clover","from":"gerry","content":"present","body":"WRONG"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Phonebook("Clover", "+15550001111", "present"), r)
    }

    // ---------- /command routing (phonebook is the auth gate) ----------

    @Test
    fun `slash command from phonebook sender produces Command route`() {
        val cfg = config(phonebook = mapOf("Neil" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Neil","content":"/info"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Command("Neil", "info", emptyList()), r)
    }

    @Test
    fun `slash command parses args`() {
        val cfg = config(phonebook = mapOf("Neil" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Neil","content":"/logs 100"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Command("Neil", "logs", listOf("100")), r)
    }

    @Test
    fun `slash command from unknown sender drops`() {
        val cfg = config(phonebook = mapOf("Neil" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"stranger","content":"/info"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
    }

    @Test
    fun `non-slash content from phonebook sender takes the forward path`() {
        val cfg = config(phonebook = mapOf("Neil" to "+15550009999"))
        val payload = """{"to":"my-phone","from":"Neil","content":"hello"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Forward("+15550009999", "hello"), r)
    }

    @Test
    fun `command verb is lowercased`() {
        val cfg = config(phonebook = mapOf("Neil" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Neil","content":"/INFO"}"""
        val r = decideRoute(payload, cfg)
        assertEquals(MqttRoute.Command("Neil", "info", emptyList()), r)
    }

    @Test
    fun `empty slash command drops`() {
        val cfg = config(phonebook = mapOf("Neil" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Neil","content":"/   "}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("empty command"))
    }

    // ---------- files parsing ----------

    @Test
    fun `forward includes files with storage URLs`() {
        val payload =
            """{"to":"my-phone","from":"Clover","content":"look",""" +
                """"files":[{"filename":"photo.jpg","storage":["https://tempfile.org/abc/"]}]}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Forward)
        val fwd = r as MqttRoute.Forward
        assertEquals(1, fwd.files.size)
        assertEquals("photo.jpg", fwd.files[0].filename)
        assertEquals(listOf("https://tempfile.org/abc/"), fwd.files[0].storageUrls)
    }

    @Test
    fun `phonebook route includes files`() {
        val payload =
            """{"to":"Clover","from":"gerry","content":"here",""" +
                """"files":[{"filename":"doc.pdf","storage":["https://tempfile.org/x/"]}]}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Phonebook)
        assertEquals(1, (r as MqttRoute.Phonebook).files.size)
    }

    @Test
    fun `files without storage array have empty URLs`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"hi","files":[{"filename":"local.txt","path":"./.files/local.txt"}]}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Forward)
        val fwd = r as MqttRoute.Forward
        assertEquals(1, fwd.files.size)
        assertEquals("local.txt", fwd.files[0].filename)
        assertTrue(fwd.files[0].storageUrls.isEmpty())
    }

    @Test
    fun `missing files array yields empty list`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"plain"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Forward)
        assertTrue((r as MqttRoute.Forward).files.isEmpty())
    }

    @Test
    fun `multiple storage URLs are preserved`() {
        val payload =
            """{"to":"my-phone","from":"Clover","content":"x",""" +
                """"files":[{"filename":"a.png","storage":["https://s1.org/1/","https://s2.org/2/"]}]}"""
        val r = decideRoute(payload, config())
        val fwd = r as MqttRoute.Forward
        assertEquals(2, fwd.files[0].storageUrls.size)
    }
}
