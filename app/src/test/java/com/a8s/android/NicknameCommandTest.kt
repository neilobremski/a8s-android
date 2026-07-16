package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NicknameCommandTest {

    @Test
    fun `add grammar names nickname before agent and normalizes lowercase`() {
        val result = NicknameCommand.parse(listOf("add", "WorkHorse", "for", "Claude-Code"))
        val add = assertInstanceOf(NicknameCommand.Action.Add::class.java, result)
        assertEquals("workhorse", add.nickname)
        assertEquals("claude-code", add.agent)
        assertFalse(add.replace)
    }

    @Test
    fun `replace is explicit`() {
        val result = NicknameCommand.parse(listOf("replace", "boss", "for", "R4T:Lead"))
        val add = assertInstanceOf(NicknameCommand.Action.Add::class.java, result)
        assertEquals("boss", add.nickname)
        assertEquals("r4t:lead", add.agent)
        assertTrue(add.replace)
    }

    @Test
    fun `legacy reversed syntax is rejected`() {
        val result = NicknameCommand.parse(listOf("claude-code", "add", "boss"))
        assertInstanceOf(NicknameCommand.Action.Invalid::class.java, result)
    }

    @Test
    fun `missing for delimiter is rejected`() {
        val result = NicknameCommand.parse(listOf("add", "boss", "claude-code"))
        assertInstanceOf(NicknameCommand.Action.Invalid::class.java, result)
    }

    @Test
    fun `multiword nickname is accepted and normalized`() {
        val result = NicknameCommand.parse(listOf("add", "the", "boss", "for", "claude-code"))
        val add = assertInstanceOf(NicknameCommand.Action.Add::class.java, result)
        assertEquals("the boss", add.nickname)
        assertEquals("claude-code", add.agent)
    }

    @Test
    fun `spoken punctuation is trimmed`() {
        val result = NicknameCommand.parse(listOf("add", "Alice,", "Node!", "for", "alice-node"))
        val add = assertInstanceOf(NicknameCommand.Action.Add::class.java, result)
        assertEquals("alice node", add.nickname)
    }

    @Test
    fun `reserved nickname is rejected`() {
        val result = NicknameCommand.parse(listOf("add", "status", "for", "claude-code"))
        assertInstanceOf(NicknameCommand.Action.Invalid::class.java, result)
    }

    @Test
    fun `known canonical names conflict case insensitively`() {
        assertTrue(
            NicknameCommand.conflictsWithCanonicalName(
                "Alice",
                device = "operator-phone",
                agents = setOf("alice", "bob"),
            ),
        )
        assertTrue(
            NicknameCommand.conflictsWithCanonicalName(
                "OPERATOR-PHONE",
                device = "operator-phone",
                agents = setOf("alice"),
            ),
        )
        assertFalse(
            NicknameCommand.conflictsWithCanonicalName(
                "ally",
                device = "operator-phone",
                agents = setOf("alice"),
            ),
        )
    }

    @Test
    fun `list supports optional agent filter`() {
        assertEquals(NicknameCommand.Action.ListFor(null), NicknameCommand.parse(listOf("list")))
        assertEquals(
            NicknameCommand.Action.ListFor("claude-code"),
            NicknameCommand.parse(listOf("list", "for", "Claude-Code")),
        )
    }

    @Test
    fun `empty command lists all mappings`() {
        assertEquals(NicknameCommand.Action.ListFor(null), NicknameCommand.parse(emptyList()))
    }
}
