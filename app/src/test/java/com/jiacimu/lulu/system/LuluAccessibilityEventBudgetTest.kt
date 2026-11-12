package com.jiacimu.lulu.system

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluAccessibilityEventBudgetTest {
    @Test fun aFloodOfContentEventsCannotScanTheTreeOnEveryCallback() {
        val b = LuluAccessibilityEventBudget()
        assertTrue(b.shouldRead("com.xhs", 1_000))
        for (now in 1_010L..1_700L step 30L) {
            assertFalse(b.shouldRead("com.xhs", now))
        }
        assertTrue(b.shouldRead("com.xhs", 1_751))
    }

    @Test fun realAppSwitchStillRefreshesWithinABoundedWindow() {
        val b = LuluAccessibilityEventBudget()
        assertTrue(b.shouldRead("com.xhs", 10_000))
        assertFalse(b.shouldRead("com.browser", 10_100))
        assertTrue(b.shouldRead("com.browser", 10_225))
        assertFalse(b.shouldRead("com.browser", 10_226))
    }

    @Test fun monotonicClockResetDoesNotFreezeObservationsForever() {
        val b = LuluAccessibilityEventBudget()
        assertTrue(b.shouldRead("com.xhs", 50_000))
        assertTrue(b.shouldRead("com.xhs", 100))
        assertFalse(b.shouldRead("", 1_000))
        b.reset()
        assertTrue(b.shouldRead("com.xhs", 101))
    }
}
