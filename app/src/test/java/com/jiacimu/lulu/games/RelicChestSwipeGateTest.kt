package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

/** The entire swipe, not every drag pixel or a held button, commits one stage. */
class RelicChestSwipeGateTest {
    @Test fun onlyCompletedForwardSwipeCommitsAPryTurn() {
        assertFalse(RelicChestSwipeGate.released(0f, 180f))
        assertFalse(RelicChestSwipeGate.released(-200f, 180f))
        assertFalse(RelicChestSwipeGate.released(120f, 180f))
        assertTrue(RelicChestSwipeGate.released(150f, 180f))
        assertTrue(RelicChestSwipeGate.released(180f, 180f))
        assertFalse(RelicChestSwipeGate.released(180f, 0f))
    }
}
