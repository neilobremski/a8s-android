package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmsStatusStorePolicyTest {

    private val part = SmsPartRef(42, 0, 1, 0, 1, "••••4567")

    @Test
    fun `request IDs increment and wrap without using zero`() {
        assertEquals(1, SmsStatusStore.nextRequestId(0))
        assertEquals(124, SmsStatusStore.nextRequestId(123))
        assertEquals(1, SmsStatusStore.nextRequestId(Int.MAX_VALUE))
    }

    @Test
    fun `sent callbacks expire before delivery reports`() {
        val sent = SmsCallbackRecord(1, 2, SmsCallbackKind.SENT, part, createdAtMs = 0)
        val delivery = SmsCallbackRecord(2, 1, SmsCallbackKind.DELIVERY, part, createdAtMs = 0)

        assertTrue(SmsStatusStore.isExpired(sent, 60L * 60L * 1000L))
        assertFalse(SmsStatusStore.isExpired(delivery, 60L * 60L * 1000L))
        assertTrue(SmsStatusStore.isExpired(delivery, 72L * 60L * 60L * 1000L))
    }
}
