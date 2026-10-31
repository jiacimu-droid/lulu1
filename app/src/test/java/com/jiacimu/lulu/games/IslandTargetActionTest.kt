package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandTargetActionTest {
    private fun scene(): BlockIslandState {
        val start = BlockIslandRules.newWorld(11, emptyList())
        val world = start.world
        val tiles = world.tiles.toMutableList()
        fun tile(x: Int, y: Int, kind: Int) { tiles[world.index(x, y)] = kind }
        tile(5, 4, 1)
        tile(4, 5, 8)
        tile(8, 8, 2)
        return start.copy(world = world.copy(
            x = 4, y = 4, tiles = tiles,
            mobs = listOf(ExpeditionMob(1, 3, 4, 3)),
            friends = listOf(ExpeditionFriend("ally", 4, 3, hp = 0)),
        ))
    }

    @Test fun nearbyResourcesAndMonstersAreImmediatelyActionable() {
        val s = scene()
        assertEquals("gather", islandTargetAction(s, 5 to 4).action)
        assertEquals("fight", islandTargetAction(s, 3 to 4).action)
        val distant = islandTargetAction(s, 8 to 8)
        assertEquals("move", distant.action)
        assertEquals(8, distant.distance)
    }

    @Test fun rescueIsExposedWithoutOpeningInventoryButRequiresRealSupplies() {
        val s = scene()
        val without = islandTargetAction(s, 4 to 3)
        assertEquals("revive", without.action)
        assertFalse(without.enabled)
        assertTrue(without.label.contains("2果"))
        val with = islandTargetAction(s.copy(fruit = 2), 4 to 3)
        assertTrue(with.enabled)
        assertEquals("救援队友", with.label)
    }

    @Test fun fishingRequiresRodAndCurrentTileCannotBeNavigatedTo() {
        val s = scene()
        assertFalse(islandTargetAction(s, 4 to 5).enabled)
        assertEquals("fish", islandTargetAction(s.copy(tools = setOf("rod")), 4 to 5).action)
        val self = islandTargetAction(s, 4 to 4)
        assertFalse(self.enabled)
        assertEquals("none", self.action)
        assertFalse(islandTargetAction(s.copy(world = s.world.copy(finished = true)), 5 to 4).enabled)
    }
}
