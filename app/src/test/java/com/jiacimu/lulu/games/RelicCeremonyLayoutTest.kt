package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

/** Keep the real committed reward and its bag/drop receipt visible on short landscape screens. */
class RelicCeremonyLayoutTest {
    @Test fun artworkShrinksOnShortLandscapeScreens() {
        assertEquals(72, relicCeremonyArtHeightDp(240f))
        assertEquals(96, relicCeremonyArtHeightDp(320f))
        assertEquals(124, relicCeremonyArtHeightDp(400f))
        assertEquals(172, relicCeremonyArtHeightDp(480f))
    }

    @Test fun artworkNeverGrowsWhenViewportShrinks() {
        var previous = 0
        for (height in 200..600) {
            val artwork = relicCeremonyArtHeightDp(height.toFloat())
            assertTrue("artwork must fit the short viewport", artwork < height)
            assertTrue("artwork height must be monotonic", artwork >= previous)
            previous = artwork
        }
    }
}
