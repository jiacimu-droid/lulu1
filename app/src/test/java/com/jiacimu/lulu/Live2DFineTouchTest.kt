package com.jiacimu.lulu

import com.jiacimu.lulu.data.InteractionSignalBridge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2DFineTouchTest {
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

    @Test fun selectedContactToolSurvivesSingleTapAndDirectionalDrag() {
        val singleTap = Live2DTouchGesture.performed("pat", "tap")
        assertEquals("slap", singleTap)
        assertTrue(InteractionSignalBridge.avatarContactDescription(
            "cheek-left", singleTap, 1)?.contains("手掌拍击") == true)
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
