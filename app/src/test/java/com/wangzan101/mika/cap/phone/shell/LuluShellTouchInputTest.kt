package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.*
import org.junit.Test

class LuluShellTouchInputTest {
    @Test fun cannotTargetOwnerDisplayAnotherAppOrUnboundedHold() {
        assertTrue(LuluShellTouchInput.allowed(3, "test.app", "test.app", 500, 900, 600))
        assertFalse(LuluShellTouchInput.allowed(0, "test.app", "test.app", 500, 900, 600))
        assertFalse(LuluShellTouchInput.allowed(3, "test.other", "test.app", 500, 900, 600))
        assertFalse(LuluShellTouchInput.allowed(3, "test.app", "test.app", -1, 900, 600))
        assertFalse(LuluShellTouchInput.allowed(3, "test.app", "test.app", 500, 900, 5001))
    }

    @Test fun partialTouchCancelsWithoutSendingAnotherClick() {
        val events = mutableListOf<String>()
        val result = LuluShellTouchInput.dispatch(
            { events += "down"; true }, { events += "up"; false }, { events += "cancel" })
        assertTrue(result.attempted)
        assertFalse(result.up)
        assertEquals(listOf("down", "up", "cancel"), events)
    }

    @Test fun rejectedOrThrowingDownDoesNotFallBackToAnotherTransport() {
        for (throwing in listOf(false, true)) {
            val events = mutableListOf<String>()
            val result = LuluShellTouchInput.dispatch(
                { events += "down"; if (throwing) error("Android result unknown"); false },
                { events += "up"; true }, { events += "cancel" })
            assertTrue(result.attempted)
            assertFalse(result.up)
            assertEquals(listOf("down", "cancel"), events)
        }
    }

    @Test fun successfulTouchHasExactlyOneDownAndOneUp() {
        val events = mutableListOf<String>()
        val result = LuluShellTouchInput.dispatch(
            { events += "down"; true }, { events += "up"; true }, { events += "cancel" })
        assertTrue(result.up)
        assertEquals(listOf("down", "up"), events)
    }
}
