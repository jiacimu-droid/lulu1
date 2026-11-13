package com.jiacimu.lulu.games

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** G-03.5: the map hint follows physical enemy state, never a cached route. */
class RelicRouteThreatRecoveryTest {
    @Test fun killedGuardRestoresDiscoveredChestRouteWithoutMovingOrAwardingLoot() {
        val original = RelicEscapeWorld.create(915, 2, emptyList(), 1, 1)
        val width = 12
        val y = 2
        val corridor = original.copy(
            w = width,
            h = 10,
            tiles = List(width * 10) { index ->
                if (index / width == y && index % width in 2..6) 0 else 1
            },
            hero = original.hero.copy(x = 2, y = y),
            chests = listOf(RelicChest(31, 6, y, RelicTier.COMMON)),
            mobs = listOf(RelicMob(91, 4, y, 5, 0)),
            sites = emptyList(),
            seen = (2..6).map { y * width + it }.toSet()
        )
        val target = RelicObjectiveGuide.visibleChest(corridor)!!
        assertNull(RelicObjectiveGuide.knownRouteTiles(corridor, target))
        assertEquals("路径未探明", RelicObjectiveGuide.optionLabel(corridor, target))

        // A real combat result changes hp; the guide may now use the same
        // discovered tiles without moving the player or granting treasure.
        val defeated = corridor.copy(mobs = corridor.mobs.map { it.copy(hp = 0) })
        val route = RelicObjectiveGuide.knownRouteTiles(defeated, target)
        assertEquals(listOf(2 to y, 3 to y, 4 to y, 5 to y), route)
        assertEquals(3, RelicObjectiveGuide.knownRouteSteps(defeated, target))
        assertEquals("已知路线约3步", RelicObjectiveGuide.optionLabel(defeated, target))
        assertEquals(2 to y, defeated.hero.x to defeated.hero.y)
        assertTrue(defeated.chests.none { it.opened })
        assertEquals(corridor.carried, defeated.carried)

        // A newly living guard blocks the hint again; no stale path survives.
        val respawned = defeated.copy(mobs = defeated.mobs.map { it.copy(hp = 5) })
        assertNull(RelicObjectiveGuide.knownRouteTiles(respawned, target))
    }
}
