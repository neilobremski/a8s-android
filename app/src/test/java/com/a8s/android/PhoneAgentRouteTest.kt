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
            """{"id":"01KTVEBX9HA8ZHMH5BP2EA38WQ","to":"operator-phone","from":"alice",""" +
                """"content":"/logs"}"""
        val result = PhoneAgentRoute.evaluate(payload, cfg) as PhoneAgentRoute.Result.Ok
        assertEquals("+15551234567", result.forward.smsToNumber)
        assertEquals("operator-phone", result.forward.targetAgent)
        assertEquals("alice", result.forward.from)
        assertEquals("/logs", result.forward.content)
    }

    @Test
    fun `MQTT to device does not phone-forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"android-pixel-7","from":"alice","content":"/info"}"""
        assertEquals(PhoneAgentRoute.Result.NotApplicable, PhoneAgentRoute.evaluate(payload, cfg))
    }

    @Test
    fun `self-loopback from local agent does not forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"operator-phone","from":"operator-phone","content":"hi"}"""
        assertEquals(PhoneAgentRoute.Result.NotApplicable, PhoneAgentRoute.evaluate(payload, cfg))
    }

    @Test
    fun `unknown to agent does not forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"unknown-agent","from":"alice","content":"hi"}"""
        assertEquals(PhoneAgentRoute.Result.NotApplicable, PhoneAgentRoute.evaluate(payload, cfg))
    }

    @Test
    fun `allow_from restricts which agents may forward SMS`() {
        val cfg = TestFixtures.config(
            principalsJson = """
                [
                  {"agent":"alice","roles":["owner"]},
                  {"agent":"stranger","roles":["owner"]},
                  {
                    "agent":"operator-phone",
                    "phone":"+15551234567",
                    "roles":["owner"],
                    "allow_from":["alice"]
                  }
                ]
            """.trimIndent(),
        )
        val allowed =
            """{"to":"operator-phone","from":"alice","content":"hi"}"""
        val denied =
            """{"to":"operator-phone","from":"stranger","content":"hi"}"""
        assertTrue(PhoneAgentRoute.evaluate(allowed, cfg) is PhoneAgentRoute.Result.Ok)
        assertEquals(
            PhoneAgentRoute.Result.Denied("operator-phone", "stranger"),
            PhoneAgentRoute.evaluate(denied, cfg),
        )
        assertNull(PhoneAgentRoute.tryForward(denied, cfg))
    }

    @Test
    fun `absent allow_from permits any sender`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"operator-phone","from":"any-cluster-agent","content":"hi"}"""
        assertTrue(PhoneAgentRoute.evaluate(payload, cfg) is PhoneAgentRoute.Result.Ok)
    }

    @Test
    fun `allow_from regex permits matching sub-agents`() {
        val cfg = TestFixtures.config(
            principalsJson = """
                [
                  {"agent":"alice","roles":["owner"]},
                  {
                    "agent":"operator-phone",
                    "phone":"+15551234567",
                    "roles":["owner"],
                    "allow_from":["alice", "alice-.*"]
                  }
                ]
            """.trimIndent(),
        )
        val direct =
            """{"to":"operator-phone","from":"alice","content":"hi"}"""
        val subAgent =
            """{"to":"operator-phone","from":"alice-laptop","content":"hi"}"""
        val denied =
            """{"to":"operator-phone","from":"stranger","content":"hi"}"""
        assertTrue(PhoneAgentRoute.evaluate(direct, cfg) is PhoneAgentRoute.Result.Ok)
        assertTrue(PhoneAgentRoute.evaluate(subAgent, cfg) is PhoneAgentRoute.Result.Ok)
        assertEquals(
            PhoneAgentRoute.Result.Denied("operator-phone", "stranger"),
            PhoneAgentRoute.evaluate(denied, cfg),
        )
    }
}
