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
        val result = PhoneAgentRoute.evaluate(payload, cfg) as PhoneAgentRoute.Result.Ok
        assertEquals("+13602196756", result.forward.smsToNumber)
        assertEquals("neil-phone", result.forward.targetAgent)
        assertEquals("knobert", result.forward.from)
        assertEquals("/logs", result.forward.content)
    }

    @Test
    fun `MQTT to device does not phone-forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"android-pixel-7","from":"knobert","content":"/info"}"""
        assertEquals(PhoneAgentRoute.Result.NotApplicable, PhoneAgentRoute.evaluate(payload, cfg))
    }

    @Test
    fun `self-loopback from local agent does not forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"neil-phone","from":"neil-phone","content":"hi"}"""
        assertEquals(PhoneAgentRoute.Result.NotApplicable, PhoneAgentRoute.evaluate(payload, cfg))
    }

    @Test
    fun `unknown to agent does not forward`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"unknown-agent","from":"knobert","content":"hi"}"""
        assertEquals(PhoneAgentRoute.Result.NotApplicable, PhoneAgentRoute.evaluate(payload, cfg))
    }

    @Test
    fun `allow_from restricts which agents may forward SMS`() {
        val cfg = TestFixtures.config(
            principalsJson = """
                [
                  {"agent":"knobert","roles":["owner"]},
                  {"agent":"stranger","roles":["owner"]},
                  {
                    "agent":"neil-phone",
                    "phone":"+13602196756",
                    "roles":["owner"],
                    "allow_from":["knobert"]
                  }
                ]
            """.trimIndent(),
        )
        val allowed =
            """{"to":"neil-phone","from":"knobert","content":"hi"}"""
        val denied =
            """{"to":"neil-phone","from":"stranger","content":"hi"}"""
        assertTrue(PhoneAgentRoute.evaluate(allowed, cfg) is PhoneAgentRoute.Result.Ok)
        assertEquals(
            PhoneAgentRoute.Result.Denied("neil-phone", "stranger"),
            PhoneAgentRoute.evaluate(denied, cfg),
        )
        assertNull(PhoneAgentRoute.tryForward(denied, cfg))
    }

    @Test
    fun `absent allow_from permits any sender`() {
        val cfg = TestFixtures.config()
        val payload = """{"to":"neil-phone","from":"any-cluster-agent","content":"hi"}"""
        assertTrue(PhoneAgentRoute.evaluate(payload, cfg) is PhoneAgentRoute.Result.Ok)
    }

    @Test
    fun `allow_from regex permits matching sub-agents`() {
        val cfg = TestFixtures.config(
            principalsJson = """
                [
                  {"agent":"knobert","roles":["owner"]},
                  {
                    "agent":"neil-phone",
                    "phone":"+13602196756",
                    "roles":["owner"],
                    "allow_from":["knobert", "knobert-.*"]
                  }
                ]
            """.trimIndent(),
        )
        val direct =
            """{"to":"neil-phone","from":"knobert","content":"hi"}"""
        val subAgent =
            """{"to":"neil-phone","from":"knobert-macbook","content":"hi"}"""
        val denied =
            """{"to":"neil-phone","from":"stranger","content":"hi"}"""
        assertTrue(PhoneAgentRoute.evaluate(direct, cfg) is PhoneAgentRoute.Result.Ok)
        assertTrue(PhoneAgentRoute.evaluate(subAgent, cfg) is PhoneAgentRoute.Result.Ok)
        assertEquals(
            PhoneAgentRoute.Result.Denied("neil-phone", "stranger"),
            PhoneAgentRoute.evaluate(denied, cfg),
        )
    }
}
