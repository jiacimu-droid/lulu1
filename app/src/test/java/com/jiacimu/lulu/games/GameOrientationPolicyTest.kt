package com.jiacimu.lulu.games

import android.content.pm.ActivityInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GameOrientationPolicyTest {
    @Test fun worldMapGamesStartInLandscape() {
        listOf("block_island", "relic_escape", "deep_sea_journey").forEach { id ->
            assertTrue(gameSupportsLandscape(id))
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                gameRequestedOrientation(id, selectionOpen = false, preferLandscape = true),
            )
        }
    }

    @Test fun returningToHallAndOtherAppsMustUsePortrait() {
        listOf("home", "memory_match", "turtle_soup", "records", "roleplay").forEach { id ->
            assertFalse(gameSupportsLandscape(id))
            assertEquals(
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
                gameRequestedOrientation(id, selectionOpen = false, preferLandscape = true),
            )
        }
    }

    @Test fun pickerAndManualPortraitNeverLockTheHostInLandscape() {
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            gameRequestedOrientation("block_island", selectionOpen = true, preferLandscape = true),
        )
        assertEquals(
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            gameRequestedOrientation("relic_escape", selectionOpen = false, preferLandscape = false),
        )
    }
}
