package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Small-screen regressions: tactical messages must yield to game controls. */
class GameHudLayoutBudgetTest {
    @Test fun narrowLandscapeReservesAllRightThumbCombatRows() {
        for (height in listOf(180f, 225f, 250f, 280f, 320f, 360f, 480f)) {
            val info=gameHudInfoMaxHeightDp(height,true)
            assertTrue("negative intel panel at ${height}dp",info>=0f)
            assertTrue("combat hotkeys displaced at ${height}dp",
                info+225f <= height.coerceAtLeast(225f)+.001f)
            assertTrue("intel exceeds landscape fraction",info<=height*.39f+.001f)
        }
        assertEquals(95f,gameHudInfoMaxHeightDp(320f,true),.001f)
        assertEquals(0f,gameHudInfoMaxHeightDp(220f,true),.001f)
    }

    @Test fun portraitPanelStillAllowsReadableInformation() {
        assertEquals(180f,gameHudInfoMaxHeightDp(600f,false),.001f)
        assertEquals(260f,gameHudInfoMaxHeightDp(1000f,false),.001f)
        assertEquals(0f,gameHudInfoMaxHeightDp(0f,false),.001f)
        assertEquals(0f,gameHudInfoMaxHeightDp(-15f,true),.001f)
    }
}
