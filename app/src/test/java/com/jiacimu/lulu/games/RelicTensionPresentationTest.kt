package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelicTensionPresentationTest {
    @Test fun escapeProgressUsesCommittedTurnsInsteadOfAnInventedClock() {
        val base = RelicEscapeWorld.create(437, 1, emptyList(), 0, 1)
        val run = base.copy(phase = RelicPhase.EXTRACT, extraction = 1, turns = 28)
        val display = relicTension(run)
        assertEquals(RelicTensionLevel.EXTRACT, display.level)
        assertEquals(1, display.completedExtractionTurns)
        assertTrue(display.headline.contains("2回合"))
        assertFalse(display.headline.contains(":"))
        assertEquals(28, run.turns)
        assertEquals(1, run.extraction)
    }

    @Test fun onlyVisibleLiveWindupInRangeCanTakeOverTheEscapeBanner() {
        val base = RelicEscapeWorld.create(817, 1, emptyList(), 0, 1)
        val x = base.hero.x
        val y = base.hero.y
        val mob = RelicMob(42, x, y, hp = 3, kind = 0, windup = true)
        val escaping = base.copy(phase = RelicPhase.EXTRACT, extraction = 2,
            mobs = listOf(mob), seen = base.seen + base.index(x, y))
        assertEquals(RelicTensionLevel.DANGER, relicTension(escaping).level)
        assertEquals(2, relicTension(escaping).completedExtractionTurns)
        assertEquals(RelicTensionLevel.EXTRACT,
            relicTension(escaping.copy(mobs = listOf(mob.copy(hp = 0)))).level)
        assertEquals(RelicTensionLevel.EXTRACT,
            relicTension(escaping.copy(seen = emptySet())).level)
        assertEquals(RelicTensionLevel.EXTRACT,
            relicTension(escaping.copy(mobs = listOf(mob.copy(windup = false)))).level)
    }

    @Test fun downedLowHealthAndGenuinePreciousLootHaveDistinctStates() {
        val base = RelicEscapeWorld.create(912, 1, emptyList(), 0, 1)
        assertEquals(RelicTensionLevel.DOWNED,
            relicTension(base.copy(phase = RelicPhase.DOWNED, downTurns = 2)).level)
        assertTrue(relicTension(base.copy(phase = RelicPhase.DOWNED, downTurns = 2))
            .headline.contains("2回合"))
        assertEquals(RelicTensionLevel.CAUTION,
            relicTension(base.copy(hero = base.hero.copy(hp = 2))).level)
        val treasure = RelicLoot("owned", "星穹冠冕", RelicTier.PRECIOUS, 430)
        val carrying = base.copy(hero = base.hero.copy(bag = listOf(treasure)))
        assertEquals(RelicTensionLevel.TREASURE, relicTension(carrying).level)
        assertTrue(relicTension(carrying).headline.contains("由你决定"))
        assertEquals(treasure, carrying.hero.bag.single())
        assertEquals(RelicTensionLevel.QUIET,
            relicTension(carrying.copy(phase = RelicPhase.FAILURE)).level)
    }
}
