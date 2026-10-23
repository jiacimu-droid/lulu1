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
        for (gesture in listOf("kiss", "kick", "slap-left", "slap-right")) {
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
}
