package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NicknamesManagerTest {

    @Test
    fun `canonical name wins over an existing nickname mapping`() {
        val result = NicknamesManager.resolveFromMappings(
            name = "Alice",
            enabled = true,
            mappings = mapOf("alice" to "alicia"),
            canonicalNames = setOf("alice", "bob"),
        )

        assertEquals("alice", result.resolved)
        assertFalse(result.matched)
        assertTrue(result.enabled)
    }

    @Test
    fun `noncanonical nickname resolves normally`() {
        val result = NicknamesManager.resolveFromMappings(
            name = "Ally",
            enabled = true,
            mappings = mapOf("ally" to "alice"),
            canonicalNames = setOf("alice", "bob"),
        )

        assertEquals("alice", result.resolved)
        assertTrue(result.matched)
    }

    @Test
    fun `longest exact leading nickname resolves tell message`() {
        val result = NicknamesManager.resolveTellFromMappings(
            args = listOf("Alice,", "Node!", "status", "please"),
            enabled = true,
            mappings = mapOf("alice" to "alice-short", "alice node" to "alice-node"),
            canonicalNames = setOf("alice-node", "alice-short"),
        )!!

        assertEquals("alice node", result.normalizedTarget)
        assertEquals("alice-node", result.resolved)
        assertEquals("status please", result.message)
        assertTrue(result.matched)
    }

    @Test
    fun `canonical first token takes precedence over longer nickname`() {
        val result = NicknamesManager.resolveTellFromMappings(
            args = listOf("alice", "node", "status"),
            enabled = true,
            mappings = mapOf("alice node" to "other-agent"),
            canonicalNames = setOf("alice"),
        )!!

        assertEquals("alice", result.resolved)
        assertEquals("node status", result.message)
        assertFalse(result.matched)
    }

    @Test
    fun `near match never routes fuzzily`() {
        val result = NicknamesManager.resolveTellFromMappings(
            args = listOf("alise", "node", "status"),
            enabled = true,
            mappings = mapOf("alice node" to "alice-node"),
            canonicalNames = setOf("alice-node"),
        )!!

        assertEquals("alise", result.resolved)
        assertFalse(result.matched)
    }
}
