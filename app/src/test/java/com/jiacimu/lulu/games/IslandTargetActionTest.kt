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

    @Test fun gatheringAndCombatUseNearbyTargetsWithoutMapSelection() {
        val s = scene()
        val tree = islandNearbyAction(s, 1 to 0, setOf("gather"))
        assertNotNull(tree)
        assertEquals("gather", tree!!.action)
        assertEquals(5, tree.x)
        assertEquals(4, tree.y)
        val chopped = BlockIslandRules.act(s, tree.action, tree.x, tree.y)
        assertEquals(0, chopped.world.cell(5,4))
        assertEquals(s.world.wood + 2, chopped.world.wood)
        assertEquals(s.world.turns + 1, chopped.world.turns)

        // Even if the last facing points at wood, the dedicated attack key
        // finds an adjacent monster instead of requiring a precision map tap.
        val enemy = islandNearbyAction(s, 1 to 0, setOf("fight"))
        assertNotNull(enemy)
        assertEquals(3, enemy!!.x)
        assertEquals(4, enemy.y)
        val struck = BlockIslandRules.act(s, enemy.action, enemy.x, enemy.y)
        assertTrue(struck.world.mobs.first().hp < s.world.mobs.first().hp)
    }

    @Test fun facingPrioritizesFrontAndNeverTeleportsToDistantResources() {
        val s = scene()
        val cells = s.world.tiles.toMutableList()
        cells[s.world.index(3,4)] = 1
        val noEnemy = s.copy(world = s.world.copy(tiles = cells,mobs=emptyList()))
        val left = islandNearbyAction(noEnemy, -1 to 0, setOf("gather"))!!
        val right = islandNearbyAction(noEnemy, 1 to 0, setOf("gather"))!!
        assertEquals(3, left.x)
        assertEquals(5, right.x)
        val blank = noEnemy.copy(world=noEnemy.world.copy(
            tiles=List(noEnemy.world.tiles.size) { 0 }, friends=emptyList()))
        assertNull(islandNearbyAction(blank, 1 to 0, setOf("gather","fight")))
        assertNull(islandNearbyAction(blank.copy(world=blank.world.copy(finished=true)),
            1 to 0, setOf("gather","fight")))
    }

    @Test fun buildPreviewUsesRealPlacementAndNeverConsumesMaterialsUntilConfirmed() {
        val start = BlockIslandRules.newWorld(41, emptyList())
        val ready = start.copy(world=start.world.copy(wood=8,stone=4))
        val preview = IslandBuildingControls.preview(ready, "workbench", 5, 5)
        assertNotNull(preview)
        assertTrue(preview!!.allowed)
        assertEquals("workbench", preview.action)
        assertFalse(ready.structures.containsKey(ready.world.index(5,5)))
        assertEquals(8,ready.world.wood)
        assertEquals(4,ready.world.stone)

        val built = BlockIslandRules.act(ready,preview.action,preview.x,preview.y)
        assertEquals("workbench",built.structures[built.world.index(5,5)])
        assertEquals(4,built.world.wood)
        assertEquals(2,built.world.stone)
        assertEquals(ready.world.turns+1,built.world.turns)
        assertFalse(IslandBuildingControls.preview(built,"workbench",5,5)!!.allowed)
    }

    @Test fun redPreviewRejectsOccupiedFarOrUnaffordableTilesWithoutAdvancingTurn() {
        val start = BlockIslandRules.newWorld(42, emptyList())
        assertFalse(IslandBuildingControls.preview(start,"wall",5,5)!!.allowed)
        val stocked = start.copy(world=start.world.copy(wood=8,stone=4))
        assertFalse(IslandBuildingControls.preview(stocked,"wall",4,5)!!.allowed)
        assertFalse(IslandBuildingControls.preview(stocked,"wall",15,15)!!.allowed)
        assertNull(IslandBuildingControls.preview(stocked,"nonexistent",5,5))
        assertNull(IslandBuildingControls.preview(stocked,"wall",-1,-1))
        val denied=BlockIslandRules.act(stocked,"wall",15,15)
        assertEquals(stocked.world.turns,denied.world.turns)
        assertEquals(stocked.world.wood,denied.world.wood)
    }

    @Test fun selectedBuildModeCanPlaceOnAnotherLegalCellWithoutExtraRecipePurchase() {
        val start = BlockIslandRules.newWorld(43, emptyList())
        val ready = start.copy(world=start.world.copy(wood=8,stone=4))
        val first=IslandBuildingControls.preview(ready,"floor",5,5)!!
        assertTrue(first.allowed)
        val placed=BlockIslandRules.act(ready,first.action,first.x,first.y)
        val second=IslandBuildingControls.preview(placed,"floor",3,5)!!
        assertTrue(second.allowed)
        val placedAgain=BlockIslandRules.act(placed,second.action,second.x,second.y)
        assertEquals(6,placedAgain.world.wood)
        assertEquals(ready.world.turns+2,placedAgain.world.turns)
        assertEquals(4,placedAgain.world.cell(5,5))
        assertEquals(4,placedAgain.world.cell(3,5))
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
