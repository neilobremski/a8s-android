package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PhoneAgentRouteTest {

    @Test
    fun `MQTT to phone agent forwards opaquely including slash commands`() {
        val cfg = TestFixtures.config()
        val payload =
            """{"id":"01KTVEBX9HA8ZHMH5BP2EA38WQ","to":"neil-phone","from":"knobert",""" +
                """"content":"/logs"}"""
        val forward = PhoneAgentRoute.tryForward(payload, cfg)
        requireNotNull(forward)
        assertEquals("+13602196756", forward.smsToNumber)
        assertEquals("neil-phone", forward.targetAgent)
        assertEquals("knobert", forward.from)
        assertEquals("/logs", forward.content)
        assertEquals("01KTVEBX9HA8ZHMH5BP2EA38WQ", forward.envelopeId)
    }

    @Test
    fun `MQTT to device does not phone-forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"android-pixel-7","from":"knobert","content":"/info"}"""
        assertNull(PhoneAgentRoute.tryForward(payload, cfg))
    }

    @Test
    fun `self-loopback from local agent does not forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"neil-phone","from":"neil-phone","content":"hi"}"""
        assertNull(PhoneAgentRoute.tryForward(payload, cfg))
    }

    @Test
    fun `unknown to agent does not forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"unknown-agent","from":"knobert","content":"hi"}"""
        assertNull(PhoneAgentRoute.tryForward(payload, cfg))
    }
}
