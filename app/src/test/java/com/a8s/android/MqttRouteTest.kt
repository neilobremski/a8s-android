package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MqttRouteTest {

    private fun config(
        device: String = "knobert-android",
        forward: String? = "+13602196756",
        phonebook: Map<String, String> = mapOf("Clover" to "+13602196756"),
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
    fun `to == device with forward set produces Forward route`() {
        val payload = """{"to":"knobert-android","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Forward("+13602196756", "Clover: hello"), r)
    }

    @Test
    fun `to == device without forward drops`() {
        val payload = """{"to":"knobert-android","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config(forward = null))
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("no forward"))
    }

    @Test
    fun `to == device with blank forward drops`() {
        val payload = """{"to":"knobert-android","from":"Clover","content":"hello"}"""
        val r = decideRoute(payload, config(forward = "   "))
        assertTrue(r is MqttRoute.Drop)
    }

    @Test
    fun `to == device with empty from omits the prefix`() {
        val payload = """{"to":"knobert-android","content":"hello"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Forward("+13602196756", "hello"), r)
    }

    @Test
    fun `to in phonebook produces Phonebook route`() {
        val payload = """{"to":"Clover","from":"gerry","content":"yo"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Phonebook("Clover", "+13602196756", "yo"), r)
    }

    @Test
    fun `to not in phonebook and not device drops`() {
        val payload = """{"to":"unknown","content":"x"}"""
        val r = decideRoute(payload, config())
        assertTrue(r is MqttRoute.Drop)
        assertTrue((r as MqttRoute.Drop).reason.contains("not in phonebook"))
    }

    @Test
    fun `missing to drops`() {
        val payload = """{"from":"x","content":"y"}"""
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
        // self-receive (Forward) path takes precedence.
        val cfg = config(phonebook = mapOf("knobert-android" to "+1999"))
        val payload = """{"to":"knobert-android","from":"Clover","content":"hi"}"""
        val r = decideRoute(payload, cfg)
        assertTrue(r is MqttRoute.Forward)
    }

    @Test
    fun `content field is read instead of body`() {
        // Regression guard for the original `body` vs `content` bug — the
        // host (Python a8s) writes envelopes with `content`, never `body`.
        val payload = """{"to":"Clover","content":"present","body":"WRONG"}"""
        val r = decideRoute(payload, config())
        assertEquals(MqttRoute.Phonebook("Clover", "+13602196756", "present"), r)
    }
}
