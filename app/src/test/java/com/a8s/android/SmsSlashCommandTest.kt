package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsSlashCommandTest {

    @Test
    fun `classify authorizes allowed command from phone principal`() {
        val cfg = TestFixtures.config()
        val result = SmsSlashCommand.classify("+13602196756", "/info", cfg)
        assertTrue(result is SmsSlashCommand.Result.Authorized)
        val auth = result as SmsSlashCommand.Result.Authorized
        assertEquals("neil-phone", auth.principal.agent)
        assertEquals("info", auth.command.name)
        assertEquals("+13602196756", auth.replyNumber)
    }

    @Test
    fun `classify rejects disallowed verb`() {
        val cfg = TestFixtures.config(
            rolesJson = """{"owner":{"commands":["*"]},"viewer":{"commands":["info"]}}""",
            principalsJson = """
                [
                  {"agent":"knobert","roles":["owner"]},
                  {"agent":"neil-phone","phone":"+13602196756","roles":["viewer"]}
                ]
            """.trimIndent(),
        )
        val result = SmsSlashCommand.classify("+13602196756", "/rm /sdcard/foo", cfg)
        assertTrue(result is SmsSlashCommand.Result.Forbidden)
        assertEquals("rm", (result as SmsSlashCommand.Result.Forbidden).verb)
    }

    @Test
    fun `classify ignores non-slash bodies`() {
        val cfg = TestFixtures.config()
        assertEquals(SmsSlashCommand.Result.NotForSms, SmsSlashCommand.classify("+13602196756", "hello", cfg))
    }

    @Test
    fun `classify ignores unknown numbers`() {
        val cfg = TestFixtures.config()
        assertEquals(SmsSlashCommand.Result.NotForSms, SmsSlashCommand.classify("+19999999999", "/info", cfg))
    }
}
