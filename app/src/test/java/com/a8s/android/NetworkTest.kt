package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkTest {

    // ---------- parseRemotes — new map shape ----------

    @Test
    fun `parseRemotes reads map with multiple remotes`() {
        val json = JSONObject("""
            {
              "remotes": {
                "hivemq": {
                  "transport": "mqtt",
                  "broker": "ssl://h:8883",
                  "topic": "n",
                  "username": "u",
                  "password": "p"
                },
                "local": {
                  "transport": "mqtt",
                  "broker": "tcp://localhost:1883",
                  "topic": "t"
                }
              }
            }
        """.trimIndent())
        val rs = Network.parseRemotes(json)
        assertEquals(2, rs.size)
        assertEquals("ssl://h:8883", rs["hivemq"]?.broker)
        assertEquals("u", rs["hivemq"]?.username)
        assertEquals("tcp://localhost:1883", rs["local"]?.broker)
        assertNull(rs["local"]?.username)
    }

    @Test
    fun `parseRemotes preserves insertion order`() {
        val json = JSONObject("""
            {"remotes":{
              "alpha":{"broker":"a","topic":"t"},
              "beta":{"broker":"b","topic":"t"},
              "gamma":{"broker":"c","topic":"t"}
            }}
        """.trimIndent())
        val keys = Network.parseRemotes(json).keys.toList()
        assertEquals(listOf("alpha", "beta", "gamma"), keys)
    }

    // ---------- parseRemotes — legacy singular ----------

    @Test
    fun `parseRemotes accepts legacy singular remote and wraps as default`() {
        // The 1.9.0 shape used a flat "remote" object with "url" instead
        // of "broker". Both must keep working until users migrate.
        val json = JSONObject("""
            {"remote": {
              "url": "ssl://legacy:8883",
              "topic": "neiltest",
              "username": "u",
              "password": "p"
            }}
        """.trimIndent())
        val rs = Network.parseRemotes(json)
        assertEquals(1, rs.size)
        val r = rs["default"]!!
        assertEquals("ssl://legacy:8883", r.broker)
        assertEquals("neiltest", r.topic)
        assertEquals("u", r.username)
    }

    @Test
    fun `parseRemotes accepts user pass aliases`() {
        val json = JSONObject("""
            {"remotes":{"x":{"broker":"b","topic":"t","user":"alice","pass":"s3cret"}}}
        """.trimIndent())
        val r = Network.parseRemotes(json)["x"]!!
        assertEquals("alice", r.username)
        assertEquals("s3cret", r.password)
    }

    @Test
    fun `parseRemotes empty when neither shape present`() {
        assertEquals(0, Network.parseRemotes(JSONObject("{}")).size)
    }

    @Test
    fun `parseRemotes rejects unknown keys`() {
        val json = JSONObject("""
            {"remotes":{"x":{"broker":"b","topic":"t","frobble":1}}}
        """.trimIndent())
        assertThrows(IllegalArgumentException::class.java) { Network.parseRemotes(json) }
    }

    // ---------- parseServices ----------

    @Test
    fun `parseServices empty when missing`() {
        assertEquals(0, Network.parseServices(JSONObject("{}")).size)
    }

    @Test
    fun `parseServices builds tempfile_org`() {
        val json = JSONObject("""
            {"services":{
              "tempfile":{"service":"tempfile_org","url":"https://tempfile.org"}
            }}
        """.trimIndent())
        val list = Network.parseServices(json)
        assertEquals(1, list.size)
        val svc = list[0]
        assertEquals("tempfile", svc.id)
        assertTrue(svc is TempFileOrgService)
    }

    @Test
    fun `parseServices forwards expiry_hours and timeout_s`() {
        val json = JSONObject("""
            {"services":{"t":{
              "service":"tempfile_org","url":"https://tempfile.org",
              "expiry_hours":6,"timeout_s":15
            }}}
        """.trimIndent())
        val list = Network.parseServices(json)
        assertTrue(list[0] is TempFileOrgService)
    }

    @Test
    fun `parseServices rejects invalid expiry`() {
        val json = JSONObject("""
            {"services":{"t":{
              "service":"tempfile_org","url":"https://tempfile.org",
              "expiry_hours":99
            }}}
        """.trimIndent())
        assertThrows(IllegalArgumentException::class.java) { Network.parseServices(json) }
    }

    @Test
    fun `parseServices rejects unknown service kind`() {
        val json = JSONObject("""
            {"services":{"x":{"service":"made-up","url":"https://x"}}}
        """.trimIndent())
        assertThrows(IllegalArgumentException::class.java) { Network.parseServices(json) }
    }

    @Test
    fun `parseServices rejects unknown spec keys`() {
        val json = JSONObject("""
            {"services":{"t":{"service":"tempfile_org","url":"https://t.org","frob":7}}}
        """.trimIndent())
        assertThrows(IllegalArgumentException::class.java) { Network.parseServices(json) }
    }
}
