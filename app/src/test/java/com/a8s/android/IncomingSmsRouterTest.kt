package com.a8s.android

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IncomingSmsRouterTest {

    @Test
    fun `lastTellTarget stores and retrieves target by sender`() {
        val sender1 = "alice-phone"
        val sender2 = "bob-phone"

        assertNull(IncomingSmsRouter.getLastTellTarget(sender1))
        assertNull(IncomingSmsRouter.getLastTellTarget(sender2))

        IncomingSmsRouter.setLastTellTarget(sender1, "server")
        assertEquals("server", IncomingSmsRouter.getLastTellTarget(sender1))
        assertNull(IncomingSmsRouter.getLastTellTarget(sender2))

        IncomingSmsRouter.setLastTellTarget(sender2, "desktop")
        assertEquals("server", IncomingSmsRouter.getLastTellTarget(sender1))
        assertEquals("desktop", IncomingSmsRouter.getLastTellTarget(sender2))

        IncomingSmsRouter.setLastTellTarget(sender1, "laptop")
        assertEquals("laptop", IncomingSmsRouter.getLastTellTarget(sender1))
    }
}
