package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigParserTest {

    @Test
    fun `parse accepts principals config`() {
        val cfg = TestFixtures.config()
        assertEquals("android-pixel-7", cfg.device)
        assertEquals(2, cfg.registry.localAgents.size)
        assertEquals("+15551234567", cfg.registry.phoneForAgent("operator-phone"))
        assertEquals(10000L, cfg.smsThrottleMs)
        assertEquals(1000, cfg.smsChunkLimit)
    }

    @Test
    fun `parse respects sms_throttle_s config`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "remotes": {"r": {"broker": "b", "topic": "t"}},
              "sms_throttle_s": 5
            }
            """.trimIndent(),
        )
        val cfg = ConfigParser.parse(json)
        assertEquals(5000L, cfg.smsThrottleMs)
    }

    @Test
    fun `parse rejects negative sms throttle`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "remotes": {"r": {"broker": "b", "topic": "t"}},
              "sms_throttle_s": -1
            }
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
    }

    @Test
    fun `parse respects sms_truncate_limit setting`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "remotes": {"r": {"broker": "b", "topic": "t"}},
              "settings": {
                "sms_truncate_limit": 500
              }
            }
            """.trimIndent(),
        )
        val cfg = ConfigParser.parse(json)
        assertEquals(10000L, cfg.smsThrottleMs)
        assertEquals(500, cfg.smsChunkLimit)
    }

    @Test
    fun `parse respects sms_raw_storage_refs setting`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "remotes": {"r": {"broker": "b", "topic": "t"}},
              "settings": {
                "sms_raw_storage_refs": true
              }
            }
            """.trimIndent(),
        )
        val cfg = ConfigParser.parse(json)
        assertTrue(cfg.smsRawStorageRefs)
    }

    @Test
    fun `parse defaults sms_raw_storage_refs to false when absent`() {
        val cfg = TestFixtures.config()
        assertFalse(cfg.smsRawStorageRefs)
    }

    @Test
    fun `parse clamps stored SMS chunk limit to minimum`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "remotes": {"r": {"broker": "b", "topic": "t"}},
              "settings": {"sms_truncate_limit": 99}
            }
            """.trimIndent(),
        )

        assertEquals(SmsSegmenter.MIN_CHUNK_CHARS, ConfigParser.parse(json).smsChunkLimit)
    }

    @Test
    fun `parse rejects unknown settings`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [{"agent": "a", "roles": ["owner"]}],
              "remotes": {"r": {"broker": "b", "topic": "t"}},
              "settings": {
                "fake_setting": 500
              }
            }
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
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
              "remotes": {"r": {"broker": "b", "topic": "t"}}
            }
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
    }

    @Test
    fun `parse allow_from on phone principal`() {
        val cfg = TestFixtures.config(
            principalsJson = """
                [
                  {"agent":"alice","roles":["owner"]},
                  {
                    "agent":"operator-phone",
                    "phone":"+15551234567",
                    "roles":["owner"],
                    "allow_from":["alice"]
                  }
                ]
            """.trimIndent(),
        )
        val p = cfg.registry.principalByAgent("operator-phone")!!
        assertEquals(listOf("alice"), p.allowFrom?.map { it.source })
        assertTrue(cfg.registry.allowsPhoneForward("alice", "operator-phone"))
        assertFalse(cfg.registry.allowsPhoneForward("stranger", "operator-phone"))
    }

    @Test
    fun `parse allow_from regex matches sub-agents`() {
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
        val p = cfg.registry.principalByAgent("operator-phone")!!
        assertEquals(listOf("alice", "alice-.*"), p.allowFrom?.map { it.source })
        assertTrue(cfg.registry.allowsPhoneForward("alice", "operator-phone"))
        assertTrue(cfg.registry.allowsPhoneForward("alice-laptop", "operator-phone"))
        assertTrue(cfg.registry.allowsPhoneForward("alice-pi", "operator-phone"))
        assertFalse(cfg.registry.allowsPhoneForward("alicex", "operator-phone"))
        assertFalse(cfg.registry.allowsPhoneForward("stranger", "operator-phone"))
    }

    @Test
    fun `parse rejects invalid allow_from regex`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [
                {
                  "agent": "p",
                  "phone": "+15551234567",
                  "roles": ["owner"],
                  "allow_from": ["alice-["]
                }
              ],
              "remotes": {"r": {"broker": "b", "topic": "t"}}
            }
            """.trimIndent(),
        )
        val ex = assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
        assertTrue(ex.message!!.contains("invalid allow_from regex"))
    }

    @Test
    fun `parse rejects allow_from on principal without phone`() {
        val json = JSONObject(
            """
            {
              "device": "d",
              "roles": {"owner": {"commands": ["*"]}},
              "principals": [
                {"agent": "a", "roles": ["owner"], "allow_from": ["b"]},
                {"agent": "b", "roles": ["owner"]}
              ],
              "remotes": {"r": {"broker": "b", "topic": "t"}}
            }
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { ConfigParser.parse(json) }
    }
}
