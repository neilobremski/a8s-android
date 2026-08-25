package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsSlashCommandTest {

    private val heyOkPrincipals = """
        [
          {"agent":"robin","roles":["owner"]},
          {"agent":"operator-phone","phone":"+15551234567","roles":["owner"]}
        ]
    """.trimIndent()

    @Test
    fun `classify authorizes allowed command from phone principal`() {
        val cfg = TestFixtures.config()
        val result = SmsSlashCommand.classify("+15551234567", "/info", cfg)
        assertTrue(result is SmsSlashCommand.Result.Authorized)
        val auth = result as SmsSlashCommand.Result.Authorized
        assertEquals("operator-phone", auth.principal.agent)
        assertEquals("info", auth.command.name)
        assertEquals("+15551234567", auth.replyNumber)
    }

    @Test
    fun `classify rejects disallowed verb`() {
        val cfg = TestFixtures.config(
            rolesJson = """{"owner":{"commands":["*"]},"viewer":{"commands":["info"]}}""",
            principalsJson = """
                [
                  {"agent":"alice","roles":["owner"]},
                  {"agent":"operator-phone","phone":"+15551234567","roles":["viewer"]}
                ]
            """.trimIndent(),
        )
        val result = SmsSlashCommand.classify("+15551234567", "/rm /sdcard/foo", cfg)
        assertTrue(result is SmsSlashCommand.Result.Forbidden)
        assertEquals("rm", (result as SmsSlashCommand.Result.Forbidden).verb)
    }

    @Test
    fun `classify ignores non-slash bodies`() {
        val cfg = TestFixtures.config()
        assertEquals(SmsSlashCommand.Result.NotForSms, SmsSlashCommand.classify("+15551234567", "hello", cfg))
    }

    @Test
    fun `classify ignores unknown numbers`() {
        val cfg = TestFixtures.config()
        assertEquals(SmsSlashCommand.Result.NotForSms, SmsSlashCommand.classify("+19999999999", "/info", cfg))
    }

    @Test
    fun `classify aliases tell without slash for Siri compatibility`() {
        val cfg = TestFixtures.config()
        val result = SmsSlashCommand.classify("+15551234567", "tell alice hello there", cfg)
        assertTrue(result is SmsSlashCommand.Result.Authorized)
        val auth = result as SmsSlashCommand.Result.Authorized
        assertEquals("tell", auth.command.name)
        assertEquals(listOf("alice", "hello", "there"), auth.command.args)
    }

    @Test
    fun `mixed-case tell with punctuated canonical target routes correctly`() {
        val cfg = TestFixtures.config(
            principalsJson = """
                [
                  {"agent":"cody","roles":["owner"]},
                  {"agent":"operator-phone","phone":"+15551234567","roles":["owner"]}
                ]
            """.trimIndent(),
        )
        val classified = SmsSlashCommand.classify(
            "+15551234567",
            "/Tell Cody, send the status update",
            cfg,
        ) as SmsSlashCommand.Result.Authorized
        val resolved = NicknamesManager.resolveTellFromMappings(
            classified.command.args,
            enabled = true,
            mappings = emptyMap(),
            canonicalNames = cfg.registry.localAgents + cfg.device,
        )!!

        assertEquals("tell", classified.command.name)
        assertEquals("Cody,", resolved.rawTarget)
        assertEquals("cody", resolved.normalizedTarget)
        assertEquals("cody", resolved.resolved)
        assertEquals("send the status update", resolved.message)
    }

    @Test
    fun `classify treats hey as a conversational tell candidate`() {
        val cfg = TestFixtures.config(principalsJson = heyOkPrincipals)
        val result = SmsSlashCommand.classify("+15551234567", "hey robin hi there", cfg)
        assertTrue(result is SmsSlashCommand.Result.ConversationalTell)
        val ct = result as SmsSlashCommand.Result.ConversationalTell
        assertEquals("tell", ct.command.name)
        assertEquals(listOf("robin", "hi", "there"), ct.command.args)
    }

    @Test
    fun `classify accepts OK and Okay case-insensitively`() {
        val cfg = TestFixtures.config(principalsJson = heyOkPrincipals)
        assertTrue(SmsSlashCommand.classify("+15551234567", "OK robin hi", cfg) is SmsSlashCommand.Result.ConversationalTell)
        assertTrue(
            SmsSlashCommand.classify("+15551234567", "Okay robin hi", cfg) is SmsSlashCommand.Result.ConversationalTell,
        )
    }

    @Test
    fun `hey delivers the same args as an equivalent tell command`() {
        val cfg = TestFixtures.config(principalsJson = heyOkPrincipals)
        val tellResult = SmsSlashCommand.classify(
            "+15551234567",
            "tell robin hi there",
            cfg,
        ) as SmsSlashCommand.Result.Authorized
        val heyResult = SmsSlashCommand.classify(
            "+15551234567",
            "hey robin hi there",
            cfg,
        ) as SmsSlashCommand.Result.ConversationalTell
        assertEquals(tellResult.command.name, heyResult.command.name)
        assertEquals(tellResult.command.args, heyResult.command.args)
    }

    @Test
    fun `conversational prefix without tell permission falls through as plain text`() {
        val cfg = TestFixtures.config(
            rolesJson = """{"owner":{"commands":["*"]},"viewer":{"commands":["info"]}}""",
            principalsJson = """
                [
                  {"agent":"robin","roles":["owner"]},
                  {"agent":"operator-phone","phone":"+15551234567","roles":["viewer"]}
                ]
            """.trimIndent(),
        )
        val result = SmsSlashCommand.classify("+15551234567", "hey robin hi", cfg)
        assertTrue(result is SmsSlashCommand.Result.NotForSms)

        val tellResult = SmsSlashCommand.classify("+15551234567", "tell robin hi", cfg)
        assertTrue(tellResult is SmsSlashCommand.Result.Forbidden)
        assertEquals("tell", (tellResult as SmsSlashCommand.Result.Forbidden).verb)
    }

    @Test
    fun `conversationalRest extracts hey ok okay and rejects everything else`() {
        assertEquals("robin hi", SmsSlashCommand.conversationalRest("hey robin hi"))
        assertEquals("robin hi", SmsSlashCommand.conversationalRest("OK robin hi"))
        assertEquals("robin hi", SmsSlashCommand.conversationalRest("Okay robin hi"))
        assertEquals("", SmsSlashCommand.conversationalRest("ok"))
        assertEquals("", SmsSlashCommand.conversationalRest("Hey"))
        assertNull(SmsSlashCommand.conversationalRest("hello"))
        assertNull(SmsSlashCommand.conversationalRest("okayness robin"))
        assertNull(SmsSlashCommand.conversationalRest("tell robin hi"))
    }
}
