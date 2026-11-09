package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Rest interaction must remain reachable after moving crafting out of the generic menu. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandCampRestTest {
    @Test fun campsiteRestAvailabilityMatchesActualPhysicalActionAndCooldown() {
        val base = BlockIslandRules.newWorld(409, emptyList())
        val world = base.world.copy(x = 5, y = 5, hp = 7, mobs = emptyList())
        val campsite = world.index(6, 5)
        val near = base.copy(world = world, structures = mapOf(campsite to "campfire"))
        assertTrue(islandCampNearby(near))
        assertTrue(islandCampRestAvailable(near))

        val healed = BlockIslandRules.act(near, "rest", world.x, world.y)
        assertEquals(world.turns + 1, healed.world.turns)
        assertEquals(11, healed.world.hp)
        assertFalse(islandCampRestAvailable(healed))
        val blocked = BlockIslandRules.act(healed, "rest", healed.world.x, healed.world.y)
        assertEquals(healed.world.turns, blocked.world.turns)

        val restedLater = healed.copy(world = healed.world.copy(turns = healed.lastRestTurn + 8))
        assertTrue(islandCampRestAvailable(restedLater))
    }

    @Test fun restNeedsRealBuildingAndInjuriesRatherThanAnOpenGameMenu() {
        val base = BlockIslandRules.newWorld(411, emptyList())
        val world = base.world.copy(x = 5, y = 5, hp = 8, mobs = emptyList())
        val withoutCamp = base.copy(world = world)
        val farCamp = withoutCamp.copy(structures = mapOf(world.index(18, 12) to "bed"))
        val atCamp = withoutCamp.copy(structures = mapOf(world.index(6, 5) to "bed"))
        assertFalse(islandCampRestAvailable(withoutCamp))
        assertFalse(islandCampRestAvailable(farCamp))
        assertTrue(islandCampRestAvailable(atCamp))
        assertFalse(islandCampRestAvailable(atCamp.copy(world = world.copy(hp = 12))))
        assertFalse(islandCampRestAvailable(atCamp.copy(lastRestTurn = world.turns)))
        assertEquals(world.turns, BlockIslandRules.act(withoutCamp, "rest", 5, 5).world.turns)
        assertEquals(world.turns, BlockIslandRules.act(farCamp, "rest", 5, 5).world.turns)
    }
}
