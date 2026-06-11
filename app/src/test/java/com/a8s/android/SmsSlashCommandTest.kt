package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsSlashCommandTest {

    private fun config(allowed: Set<String> = SmsCommandPolicy.DEFAULT_ALLOWED) = A8sAndroid.Config(
        device = "my-phone",
        phonebook = mapOf("Neil" to "+1 360-219-6756"),
        remotes = emptyMap(),
        services = emptyList(),
        smsAllowedCommands = allowed,
    )

    @Test
    fun `classify authorizes allowed command from phonebook number`() {
        val r = SmsSlashCommand.classify("3602196756", "/info", config())
        val authorized = assertInstanceOf(SmsSlashCommand.Result.Authorized::class.java, r)
        assertEquals("Neil", authorized.participantName)
        assertEquals("+1 360-219-6756", authorized.replyNumber)
        assertEquals("info", authorized.command.name)
        assertEquals("+1 360-219-6756", authorized.command.smsReplyTo)
    }

    @Test
    fun `classify rejects disallowed verb as Forbidden`() {
        val r = SmsSlashCommand.classify("3602196756", "/update http://evil/x.apk", config())
        val forbidden = assertInstanceOf(SmsSlashCommand.Result.Forbidden::class.java, r)
        assertEquals("update", forbidden.verb)
        assertEquals("+1 360-219-6756", forbidden.replyNumber)
    }

    @Test
    fun `classify allows dangerous verb when wildcard configured`() {
        val r = SmsSlashCommand.classify("3602196756", "/update", config(setOf("*")))
        assertInstanceOf(SmsSlashCommand.Result.Authorized::class.java, r)
    }

    @Test
    fun `classify returns NotForSms for non-slash body`() {
        assertTrue(SmsSlashCommand.classify("3602196756", "hello", config()) is SmsSlashCommand.Result.NotForSms)
    }

    @Test
    fun `classify returns NotForSms for unknown sender`() {
        assertTrue(SmsSlashCommand.classify("+19999999999", "/info", config()) is SmsSlashCommand.Result.NotForSms)
    }

    @Test
    fun `classify lowercases verb and keeps args`() {
        val r = SmsSlashCommand.classify("3602196756", "/TELL Bob hello there", config())
        val authorized = assertInstanceOf(SmsSlashCommand.Result.Authorized::class.java, r)
        assertEquals("tell", authorized.command.name)
        assertEquals(listOf("Bob", "hello", "there"), authorized.command.args)
    }
}
