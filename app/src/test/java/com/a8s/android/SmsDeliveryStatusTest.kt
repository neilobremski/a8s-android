package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SmsDeliveryStatusTest {

    @Test
    fun `3gpp complete pending and failed ranges are distinct`() {
        listOf(0x00, 0x01, 0x1f).forEach {
            assertEquals(SmsDeliveryClass.POSITIVE, SmsDeliveryStatus.classify3gpp(it))
        }
        listOf(0x20, 0x21, 0x3f).forEach {
            assertEquals(SmsDeliveryClass.PENDING, SmsDeliveryStatus.classify3gpp(it))
        }
        listOf(0x40, 0x7f, 0xff).forEach {
            assertEquals(SmsDeliveryClass.NEGATIVE, SmsDeliveryStatus.classify3gpp(it))
        }
    }

    @Test
    fun `3gpp2 uses shifted error class`() {
        assertEquals(SmsDeliveryClass.POSITIVE, SmsDeliveryStatus.classify3gpp2(0x0002_0000))
        assertEquals(SmsDeliveryClass.NEGATIVE, SmsDeliveryStatus.classify3gpp2(0x0102_0000))
        assertEquals(SmsDeliveryClass.PENDING, SmsDeliveryStatus.classify3gpp2(0x0202_0000))
        assertEquals(SmsDeliveryClass.NEGATIVE, SmsDeliveryStatus.classify3gpp2(0x0302_0000))
    }

    @Test
    fun `unknown format is undecodable`() {
        assertEquals(SmsDeliveryClass.UNDECODABLE, SmsDeliveryStatus.classify("unknown", 0))
        assertEquals(SmsDeliveryClass.UNDECODABLE, SmsDeliveryStatus.classify(null, 0))
    }
}
