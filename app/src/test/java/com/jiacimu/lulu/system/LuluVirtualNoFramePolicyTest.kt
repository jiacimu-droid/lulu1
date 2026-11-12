package com.jiacimu.lulu.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LuluVirtualNoFramePolicyTest {
    private fun next(previous: Int, verified: Boolean = true,
        effect: String = "unknown", now: String? = "a", before: String? = "a",
        input: Boolean = false): Int =
        LuluVirtualNoFramePolicy.nextStalledCount(previous, false, verified,
            effect, now, before, input)

    @Test fun validAccessibilityProgressSurvivesMissingPixels() {
        assertEquals(0, next(3, effect = "tree_changed"))
        assertEquals(0, next(3, effect = "app_visible"))
        assertEquals(0, next(3, effect = "input_verified"))
        assertEquals(0, next(3, now = "new", before = "old"))
        assertEquals(0, next(3, input = true))
    }

    @Test fun noWindowOrRepeatedUnchangedScreenStopsBoundedly() {
        assertEquals(2, next(1))
        assertEquals(2, next(1, verified = false, effect = "tree_changed"))
        assertTrue(LuluVirtualNoFramePolicy.exhausted(next(3)))
        assertFalse(LuluVirtualNoFramePolicy.exhausted(next(1)))
    }

    @Test fun realImageImmediatelyClearsStalledCount() {
        assertEquals(0, LuluVirtualNoFramePolicy.nextStalledCount(
            3, true, false, "unknown", null, null, false))
    }
}
