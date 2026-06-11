package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SubIdentityRouteTest {

    private fun config(
        phonebook: Map<String, String> = mapOf("Neil" to "+1 360-219-6756"),
        tellPrefix: String = "text",
    ): A8sAndroid.Config = A8sAndroid.Config(
        device = "my-phone",
        phonebook = phonebook,
        remotes = emptyMap(),
        services = emptyList(),
        tellPrefix = tellPrefix,
    )

    @Test
    fun `tryForward maps to SMS for sub-identity recipient`() {
        val payload = """
            {"id":"01JABCDEFGHJKMNPQRSTVWXYZ0","from":"Bob","to":"text-13602196756","content":"hello"}
        """.trimIndent()
        val forward = SubIdentityRoute.tryForward(payload, config())
        assertEquals("+1 360-219-6756", forward?.smsToNumber)
        assertEquals("Bob", forward?.from)
        assertEquals("hello", forward?.content)
    }

    @Test
    fun `tryForward drops loopback from own sub-identity`() {
        val payload = """
            {"id":"01JABCDEFGHJKMNPQRSTVWXYZ0","from":"text-13602196756","to":"text-13602196756","content":"echo"}
        """.trimIndent()
        assertNull(SubIdentityRoute.tryForward(payload, config()))
    }

    @Test
    fun `tryForward ignores non sub-identity to`() {
        val payload = """{"from":"Bob","to":"my-phone","content":"/info"}"""
        assertNull(SubIdentityRoute.tryForward(payload, config()))
    }
}
