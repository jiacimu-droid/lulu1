package com.jiacimu.lulu

import com.jiacimu.lulu.data.InteractionSignalBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2DFineTouchTest {
    @Test fun lateRendererTouchCannotLeakAcrossCallsOrDisposedStages() {
        assertTrue(Live2DTouchGesture.acceptsCompletion("call-A", "call-A", true, true))
        // Preview/diagnostic stages legitimately have no call ID.
        assertTrue(Live2DTouchGesture.acceptsCompletion("", "", true, true))
        assertFalse(Live2DTouchGesture.acceptsCompletion("", "call-A", true, true))
        assertFalse(Live2DTouchGesture.acceptsCompletion("call-A", "call-B", true, true))
        assertFalse(Live2DTouchGesture.acceptsCompletion("call-A", "call-A", false, true))
        assertFalse(Live2DTouchGesture.acceptsCompletion("call-A", "call-A", true, false))
    }

    @Test fun fineTouchesPreserveActualBodyPartInExistingCallTimelineContract() {
        for ((region, label) in Live2DTouchZones.labels) {
            assertEquals(label, Live2DTouchZones.label(region))
            val description =
                InteractionSignalBridge.avatarContactDescription(region, "stroke-left", 1)
            assertTrue("missing touch region: $region", description?.contains(label) == true)
        }
        for (gesture in listOf("kiss", "slap", "slap-left", "slap-right")) {
            val contact = InteractionSignalBridge.avatarContactDescription(
                "cheek-left", gesture, 1)
            assertTrue("new video contact $gesture must be a real timeline fact",
                contact?.contains("左脸颊") == true)
        }
        assertNull(Live2DTouchZones.label("background"))
        assertNull(InteractionSignalBridge.avatarContactDescription("background", "tap", 1))
        // DOWN and MOVE act on the local body; they are not user conversation events.
        assertNull(InteractionSignalBridge.avatarContactDescription("hair", "down", 1))
    }

    @Test fun slowCaressAccumulatesMoveFramesAndBoundaryReentryIsImmediate() {
        val slop = 8f
        // Each 120Hz frame moves only .5px: none alone clears the old 1px
        // cutoff, but the accumulated 80ms path is a real stroke.
        assertFalse(Live2DTouchGesture.shouldDispatchMove(.5f, 16L, slop))
        assertFalse(Live2DTouchGesture.shouldDispatchMove(2.5f, 64L, slop))
        assertTrue(Live2DTouchGesture.shouldDispatchMove(2.5f, 80L, slop))
        assertFalse(Live2DTouchGesture.shouldDispatchMove(.5f, 100L, slop))
        assertTrue(Live2DTouchGesture.shouldDispatchMove(0f, 4L, slop, true))
        assertFalse(Live2DTouchGesture.shouldDispatchMove(Float.NaN, 100L, slop, true))
        assertFalse(Live2DTouchGesture.shouldDispatchMove(2f, 100L, Float.NaN))
    }

    @Test fun completedGestureUsesWholePathNotLastJitter() {
        val slop = 8f
        assertEquals("stroke-right", Live2DTouchGesture.releasedGesture(
            96f, -4f, 130f, slop, 180L, "stroke-left"))
        assertEquals("stroke-left", Live2DTouchGesture.releasedGesture(
            -96f, 4f, 130f, slop, 180L, "stroke-right"))
        assertEquals("stroke-down", Live2DTouchGesture.releasedGesture(
            3f, 90f, 115f, slop, 180L, "stroke-left"))
        assertEquals("stroke-up", Live2DTouchGesture.releasedGesture(
            -2f, -90f, 115f, slop, 180L, "stroke-right"))
        assertEquals("stroke-left", Live2DTouchGesture.releasedGesture(
            1f, 1f, 190f, slop, 180L, "stroke-left"))
        assertEquals("hold", Live2DTouchGesture.releasedGesture(
            0f, 0f, 190f, slop, 180L, null))
        assertEquals("tap", Live2DTouchGesture.releasedGesture(
            1f, 1f, 5f, slop, 120L, null))
        assertEquals("hold", Live2DTouchGesture.releasedGesture(
            1f, 1f, 5f, slop, 600L, null))
        assertEquals("slap-right", Live2DTouchGesture.performed("pat",
            Live2DTouchGesture.releasedGesture(96f, 0f, 130f, slop, 180L, "stroke-left")))
        assertEquals("kiss", Live2DTouchGesture.performed("kiss",
            Live2DTouchGesture.releasedGesture(96f, 0f, 130f, slop, 180L, "stroke-left")))
    }

    @Test fun selectedContactToolSurvivesSingleTapAndDirectionalDrag() {
        val singleTap = Live2DTouchGesture.performed("pat", "tap")
        assertEquals("slap", singleTap)
        assertTrue(InteractionSignalBridge.avatarContactDescription(
            "cheek-left", singleTap, 1)?.contains("手掌扇了一下") == true)
        assertEquals("slap-left", Live2DTouchGesture.performed("pat", "stroke-left"))
        assertEquals("slap-right", Live2DTouchGesture.performed("pat", "stroke-right"))
        for (hand in listOf("tap", "hold", "stroke-left", "stroke-down")) {
            assertEquals("kiss", Live2DTouchGesture.performed("kiss", hand))
            assertEquals(hand, Live2DTouchGesture.performed("hand", hand))
        }
        assertTrue(InteractionSignalBridge.avatarContactDescription(
            "throat", "kiss", 1, .9f)?.contains("喉结（力度重）") == true)
        assertTrue(InteractionSignalBridge.avatarContactDescription(
            "beast-ear-left", "stroke", 1, .25f)?.contains("左兽耳（力度轻）") == true)
        assertNull(InteractionSignalBridge.avatarContactDescription(
            "cheek-left", "slap", 1, Float.NaN))
        assertTrue(InteractionSignalBridge.avatarContactDescription(
            "cheek-left", "stroke-left", 1, .55f, 12_500L)
            ?.contains("手势总时长12.5秒") == true)
    }
}
