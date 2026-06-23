package com.a8s.android

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsCommandPolicyTest {

    @Test
    fun `default allows safe verbs`() {
        for (verb in listOf("info", "logs", "trace", "location", "tell", "screenshot")) {
            assertTrue(SmsCommandPolicy.isAllowed(verb, SmsCommandPolicy.DEFAULT_ALLOWED), "expected $verb allowed")
        }
    }

    @Test
    fun `default denies destructive verbs`() {
        for (verb in listOf("update", "rm", "cat", "send", "mms", "reply", "macro", "tap", "input")) {
            assertFalse(SmsCommandPolicy.isAllowed(verb, SmsCommandPolicy.DEFAULT_ALLOWED), "expected $verb denied")
        }
    }

    @Test
    fun `wildcard allows everything`() {
        assertTrue(SmsCommandPolicy.isAllowed("update", setOf("*")))
        assertTrue(SmsCommandPolicy.isAllowed("rm", setOf("*")))
    }

    @Test
    fun `match is case-insensitive`() {
        assertTrue(SmsCommandPolicy.isAllowed("INFO", SmsCommandPolicy.DEFAULT_ALLOWED))
    }
}
