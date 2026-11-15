package com.jiacimu.lulu.games

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IslandTeamSpeechWakeTest {
    @Test fun realPlayerSpeechIsDeliveredWhileMovementContinues() {
        assertTrue(shouldWakeIslandTeam(true, true, 1, true))
        assertTrue(shouldWakeIslandTeam(true, false, 1, false))
        assertFalse(shouldWakeIslandTeam(true, true, 1, false))
        assertFalse(shouldWakeIslandTeam(false, true, 1, true))
        assertFalse(shouldWakeIslandTeam(true, false, 0, true))
    }
}
