package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class LuluCallFloatingWindowTest {
    @Test fun connectingAndConnectedCallsAreVisibleWhileMinimized() {
        assertTrue(shouldShowFloatingCall(CallPhase.Dialing, false, "role-one"))
        assertTrue(shouldShowFloatingCall(CallPhase.Connected, false, "role-one"))
        assertFalse(shouldShowFloatingCall(CallPhase.Connected, true, "role-one"))
    }

    @Test fun idleReadyAndEndedSessionsNeverLeaveGhostAvatar() {
        assertFalse(shouldShowFloatingCall(CallPhase.Idle, false, "role-one"))
        assertFalse(shouldShowFloatingCall(CallPhase.Ready, false, "role-one"))
        assertFalse(shouldShowFloatingCall(CallPhase.Ended, false, "role-one"))
        assertFalse(shouldShowFloatingCall(CallPhase.Connected, false, ""))
    }

    @Test fun minimizingAndRestoringPresentationDoNotHangUp() {
        LuluCallWindowController.show()
        assertTrue(LuluCallWindowController.expanded.value)
        LuluCallWindowController.minimize()
        assertFalse(LuluCallWindowController.expanded.value)
        LuluCallWindowController.show()
        assertTrue(LuluCallWindowController.expanded.value)
        LuluCallWindowController.minimize()
    }
    @Test fun voiceAndVideoChoiceSurviveMinimizingWithoutStartingAnotherCall() {
        LuluCallWindowController.start(video = true)
        assertTrue(LuluCallWindowController.videoRequested.value)
        LuluCallWindowController.minimize()
        LuluCallWindowController.show()
        assertTrue(LuluCallWindowController.videoRequested.value)
        LuluCallWindowController.setVideoMode(false)
        assertFalse(LuluCallWindowController.videoRequested.value)
        LuluCallWindowController.minimize()
    }

}
