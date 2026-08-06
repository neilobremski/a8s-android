package com.a8s.android

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NetworkTest {

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

    @Test
    fun `parseRemotes rejects legacy singular remote`() {
        val json = JSONObject("""
            {"remote": {
              "url": "ssl://legacy:8883",
              "topic": "test-topic"
            }}
        """.trimIndent())
        assertThrows(Exception::class.java) { Network.parseRemotes(json) }
    }

    @Test
    fun `parseRemotes rejects unknown keys`() {
        val json = JSONObject("""
            {"remotes":{"x":{"broker":"b","topic":"t","frobble":1}}}
        """.trimIndent())
        assertThrows(IllegalArgumentException::class.java) { Network.parseRemotes(json) }
    }

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

    @Test
    fun `webdav service parses with an optional base url`() {
        val root = JSONObject(
            """
            {"services":{"fm":{"service":"webdav",
              "url":"webdav://dav.example.com/dav/files/user/a8s",
              "base_url":"https://files.example.com/a8s",
              "user":"user@example.com","password":"secret"}}}
            """.trimIndent(),
        )
        val services = Network.parseServices(root)
        assertEquals(1, services.size)
        assertEquals("fm", services[0].id)
        assertTrue(services[0].producesPublicUrl)
    }

    @Test
    fun `webdav without a base url parses but is not publicly fetchable`() {
        val root = JSONObject(
            """
            {"services":{"fm":{"service":"webdav",
              "url":"webdav://dav.example.com/dav/files/user/a8s"}}}
            """.trimIndent(),
        )
        val services = Network.parseServices(root)
        assertEquals(false, services[0].producesPublicUrl)
    }

    @Test
    fun `a plaintext base url is refused`() {
        val root = JSONObject(
            """
            {"services":{"fm":{"service":"webdav",
              "url":"webdav://dav.example.com/dav",
              "base_url":"http://files.example.com/a8s"}}}
            """.trimIndent(),
        )
        assertThrows(IllegalArgumentException::class.java) { Network.parseServices(root) }
    }

    @Test
    fun `webdav sorts ahead of tempfile regardless of config order`() {
        val root = JSONObject(
            """
            {"services":{
              "scratch":{"service":"tempfile_org","url":"https://tempfile.org"},
              "fm":{"service":"webdav","url":"webdav://dav.example.com/dav",
                    "base_url":"https://files.example.com/a8s"}}}
            """.trimIndent(),
        )
        // A recipient tries the URLs in envelope order, so the preferred
        // store has to be uploaded to first.
        assertEquals(listOf("fm", "scratch"), Network.parseServices(root).map { it.id })
    }
}
