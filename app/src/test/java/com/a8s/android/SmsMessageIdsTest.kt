package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmsMessageIdsTest {
    @Test
    fun `message IDs increment and wrap to one`() {
        assertEquals(1, SmsMessageIds.nextAfter(0))
        assertEquals(124, SmsMessageIds.nextAfter(123))
        assertEquals(1, SmsMessageIds.nextAfter(Int.MAX_VALUE))
    }
}
