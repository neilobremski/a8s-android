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
}
