package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

/** Story visibility and model generation must not own the locomotion lifecycle. */
class MeetingWalkAndTalkContractTest {
    @Test fun digitalMeetingKeepsPlayerControlsEnabledForEntireLiveSession() {
        // The chat overlay, autonomous reply, and story page are intentionally
        // NOT arguments to this policy: none may suspend joystick/tap navigation.
        assertTrue(meetingSceneAllowsMovement(isDigitalWorld = true, viewOnly = false))
        assertFalse(meetingSceneAllowsMovement(isDigitalWorld = true, viewOnly = true))
        assertFalse(meetingSceneAllowsMovement(isDigitalWorld = false, viewOnly = false))
        assertFalse(meetingSceneAllowsMovement(isDigitalWorld = false, viewOnly = true))
    }
}
