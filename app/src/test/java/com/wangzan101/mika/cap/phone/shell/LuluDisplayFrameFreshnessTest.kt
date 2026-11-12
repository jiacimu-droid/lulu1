package com.wangzan101.mika.cap.phone.shell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Mika-style frame timestamps must be compared only within one owned
 * ImageReader, not to wall-clock time or display 0 screenshots.
 */
class LuluDisplayFrameFreshnessTest {
    @Test fun staleFrameBeforeDispatchedSwipeMustBeRejected() {
        val barrier = LuluDisplayFrameFreshness.barrier(
            lastDeliveredNanos = 110L, drainedNanos = 140L)
        assertEquals(140L, barrier)
        assertFalse(LuluDisplayFrameFreshness.accepts(139L, barrier))
        assertFalse(LuluDisplayFrameFreshness.accepts(140L, barrier))
        assertTrue(LuluDisplayFrameFreshness.accepts(141L, barrier))
    }

    @Test fun observeWithoutPendingActionCanStillReadCurrentPage() {
        assertTrue(LuluDisplayFrameFreshness.accepts(555L, 0L))
        assertFalse(LuluDisplayFrameFreshness.accepts(0L, 0L))
    }

    @Test fun newnessIsNotEquivalentToVisualChangeOrTaskSuccess() {
        assertFalse(LuluDisplayFrameFreshness.isNewer(100L, 100L))
        assertTrue(LuluDisplayFrameFreshness.isNewer(101L, 100L))
        // Frame freshness only proves a newly produced buffer; it does NOT
        // prove scroll movement, new video, or completion of a user's task.
    }

    @Test fun actionBarrierNeverMovesBackward() {
        assertEquals(220L, LuluDisplayFrameFreshness.barrier(220L, 210L))
        assertEquals(0L, LuluDisplayFrameFreshness.barrier(0L, -1L))
    }
}
