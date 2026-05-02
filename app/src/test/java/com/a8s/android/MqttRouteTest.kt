package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqttRouteTest {

    private fun config(
        device: String = "my-phone",
        forward: String? = "+15550001111",
        phonebook: Map<String, String> = mapOf("Clover" to "+15550001111"),
    ): A8sAndroid.Config = A8sAndroid.Config(
        device = device,
        forward = forward,
        phonebook = phonebook,
        remote = A8sAndroid.RemoteConfig(
            url = "ssl://broker:8883",
            topic = "t",
            username = "u",
            password = "p",
        ),
    )

    @Test
    fun `to == device with forward set and known sender produces Forward route`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Forward("+15550001111", "Clover: hello"), r)
    }

    @Test
    fun `to == device without forward drops`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config(forward = null))
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("no forward"))
    }

    @Test
    fun `to == device with blank forward drops`() {
        val payload = """{"to":"my-phone","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config(forward = "   "))
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `to == device from unknown sender is dropped (sender verification)`() {
        // Forward path requires `from` to be a known phonebook participant.
        // An untrusted/unknown sender on the cluster cannot reach the
        // operator's phone via this device.
        val payload = """{"to":"my-phone","from":"stranger","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
    }

    @Test
    fun `to == device with empty from is dropped`() {
        // Empty `from` can't be a phonebook participant, so the forward
        // gate rejects it. This also catches malformed envelopes.
        val payload = """{"to":"my-phone","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `self-loopback (from equals device) is dropped`() {
        // The MQTT broker echoes our own publishes back. We must not
        // re-route those as fresh SMS.
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
        // If somebody puts the device name in the phonebook too, the
        // self-receive (Forward) path takes precedence — provided the
        // sender is also in the phonebook.
        val cfg = config(phonebook = mapOf("my-phone" to "+1999", "Clover" to "+15550001111"))
        val payload = """{"to":"my-phone","from":"Clover","content":"hi"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Forward)
    }

    @Test
    fun `content field is read instead of body`() {
        // Regression guard for the original `body` vs `content` bug — the
        // host (Python a8s) writes envelopes with `content`, never `body`.
        val payload = """{"to":"Clover","from":"gerry","content":"present","body":"WRONG"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Phonebook("Clover", "+15550001111", "present"), r)
    }
}
