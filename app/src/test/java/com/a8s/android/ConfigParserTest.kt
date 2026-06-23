package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigParserTest {

    @Test
    fun `parse accepts principals config`() {
        val cfg = TestFixtures.config()
        assertEquals("android-pixel-7", cfg.device)
        assertEquals(2, cfg.registry.localAgents.size)
        assertEquals("knobert", cfg.routing.smsInboundAgent)
        assertEquals("+13602196756", cfg.registry.phoneForAgent("neil-phone"))
    }

    @Test
    fun `parse rejects legacy phonebook key`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "phonebook": {"A": "+1"},
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "routing": {"sms_inbound_agent": "a"},
              "remotes": {"r": {"broker": "b", "topic": "t"}}
            }
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
    }

    @Test
    fun `parse rejects principal agent equal to device`() {
        val json = JSONObject(
            """
            {
              "device": "same",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "same", "roles": ["owner"]}],
              "routing": {"sms_inbound_agent": "same"},
              "remotes": {"r": {"broker": "b", "topic": "t"}}
            }
            """.trimIndent(),
        )
        val ex = assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
        assertTrue(ex.message!!.contains("must not equal device"))
    }

    @Test
    fun `parse requires owner role`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"viewer": {"commands": ["info"]}},
              "principals": [{"agent": "a", "roles": ["viewer"]}],
              "routing": {"sms_inbound_agent": "a"},
              "remotes": {"r": {"broker": "b", "topic": "t"}}
            }
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
    }
}
