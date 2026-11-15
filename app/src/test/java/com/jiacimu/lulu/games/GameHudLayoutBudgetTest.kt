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

    @Test fun compactVitalsNeverRecreateAFullWidthTopHud() {
        assertEquals(184.8f, gameHudCompactStatusMaxWidthDp(660f, true), .001f)
        assertEquals(194f, gameHudCompactStatusMaxWidthDp(1024f, true), .001f)
        assertEquals(187.2f, gameHudCompactStatusMaxWidthDp(360f, false), .001f)
        assertEquals(0f, gameHudCompactStatusMaxWidthDp(-1f, true), .001f)
        assertTrue(gameHudCompactStatusMaxWidthDp(660f, true) < 660f * .30f)
    }

    @Test fun worldGameTopRightPanelsNeverCoverHostNavigation() {
        // Game host owns the first 40dp at the top right (back, orientation,
        // audio and game-model buttons); both overlays start below that band.
        assertEquals(48f, gameHudChromeSafeTopDp(), .001f)
        assertTrue(gameHudChromeSafeTopDp() >= 40f)
        assertEquals(8f, gameHudChromeSafeTopDp(0f), .001f)
        for (height in listOf(0f, 120f, 200f, 235f, 360f, 600f)) {
            val drawer = gameHudDrawerMaxHeightDp(height)
            val team = gameHudTeamPanelHeightDp(height, landscape = true)
            assertTrue("drawer exceeds vertical safe area at ${height}dp",
                drawer + gameHudChromeSafeTopDp() + 12f <=
                    height.coerceAtLeast(60f) + .001f)
            assertTrue("team panel overflows at ${height}dp",
                team <= height * .52f + .001f)
            assertTrue("team panel exceeds its visual cap", team <= 170f)
        }
        assertEquals(122.2f, gameHudTeamPanelHeightDp(235f, true), .001f)
        assertEquals(115.2f, gameHudTeamPanelHeightDp(360f, false), .001f)
    }

    @Test fun portraitPanelStillAllowsReadableInformation() {
        assertEquals(180f,gameHudInfoMaxHeightDp(600f,false),.001f)
        assertEquals(260f,gameHudInfoMaxHeightDp(1000f,false),.001f)
        assertEquals(0f,gameHudInfoMaxHeightDp(0f,false),.001f)
        assertEquals(0f,gameHudInfoMaxHeightDp(-15f,true),.001f)
    }
}
