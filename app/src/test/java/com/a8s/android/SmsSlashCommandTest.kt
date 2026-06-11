package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class SmsSlashCommandTest {

    private val config = A8sAndroid.Config(
        device = "my-phone",
        phonebook = mapOf("Neil" to "+1 360-219-6756"),
        remotes = emptyMap(),
        services = emptyList(),
    )

    @Test
    fun `tryParse authorizes slash command from phonebook number`() {
        val authorized = SmsSlashCommand.tryParse("3602196756", "/info", config)
        assertEquals("Neil", authorized?.participantName)
        assertEquals("+1 360-219-6756", authorized?.replyNumber)
        assertEquals("info", authorized?.command?.name)
        assertEquals("+1 360-219-6756", authorized?.command?.smsReplyTo)
    }

    @Test
    fun `tryParse rejects non-slash body`() {
        assertNull(SmsSlashCommand.tryParse("3602196756", "hello", config))
    }

    @Test
    fun `tryParse rejects unknown sender`() {
        assertNull(SmsSlashCommand.tryParse("+19999999999", "/info", config))
    }

    @Test
    fun `parseSlashContent splits verb and args`() {
        val parsed = SmsSlashCommand.parseSlashContent("/tell Bob hello there")
        assertEquals("tell", parsed?.name)
        assertEquals(listOf("Bob", "hello", "there"), parsed?.args)
    }
}
