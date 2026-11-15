package com.jiacimu.lulu.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android permission callback may arrive after STOP or a new game session. */
class GameMicPermissionGateTest {
    @Test fun permissionResultOnlyStartsTheOriginalVisibleRequestOnce() {
        val gate = GameMicPermissionGate()
        assertFalse(gate.consume())
        gate.request()
        assertTrue(gate.consume())
        assertFalse(gate.consume())
    }

    @Test fun permissionResultAfterBackgroundOrLeavingGameCannotResurrectMic() {
        val oldScreen = GameMicPermissionGate()
        oldScreen.request()
        oldScreen.invalidate() // ON_STOP / DisposableEffect
        assertFalse(oldScreen.consume()) // late Android grant
        val newScreen = GameMicPermissionGate()
        assertFalse(newScreen.consume()) // old grant cannot start new game
        newScreen.request()
        assertTrue(newScreen.consume()) // explicit new request still works
    }

    @Test fun repeatedStopAndLateDenialAreIdempotent() {
        val gate = GameMicPermissionGate()
        gate.request()
        gate.invalidate()
        gate.invalidate()
        assertFalse(gate.consume())
        gate.request()
        assertTrue(gate.consume())
    }
}
