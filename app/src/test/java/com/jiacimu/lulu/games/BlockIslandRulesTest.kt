package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The new sandbox has its own rules, and cannot change treasure extraction outcomes. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BlockIslandRulesTest {
    @Test fun mapIsConnectedAtSpawnAndCreationIsRepeatable() {
        val a = BlockIslandRules.newWorld(101, listOf("roleA", "roleB"))
        val b = BlockIslandRules.newWorld(101, listOf("roleA", "roleB"))
        assertEquals(a.world.tiles, b.world.tiles)
        assertEquals(28, a.world.width)
        assertEquals(22, a.world.height)
        assertEquals(2, a.world.friends.size)
        assertTrue(BlockIslandRules.isWalkable(a, a.world.x, a.world.y))
        assertTrue(BlockIslandRules.isWalkable(a, 5, 5))
        assertNotNull(BlockIslandRules.nextStep(a, a.world.x, a.world.y, 15, 5))
        assertTrue(a.world.mobs.all { BlockIslandRules.isWalkable(a, it.x, it.y) })
    }

    @Test fun buildingToolsAndSurvivalUseRealSharedInventoryAndPersist() {
        val a = BlockIslandRules.newWorld(6, emptyList())
            .copy(world = BlockIslandRules.newWorld(6, emptyList()).world.copy(wood = 14, stone = 10, hp = 6))
        val table = BlockIslandRules.act(a, "workbench", 5, 5)
        assertEquals("workbench", table.structures[table.world.index(5, 5)])
        assertEquals(10, table.world.wood)
        assertEquals(8, table.world.stone)
        assertFalse(BlockIslandRules.isWalkable(table, 5, 5))
        val axe = BlockIslandRules.act(table, "craft_axe")
        assertTrue("axe" in axe.tools)
        assertEquals(8, axe.world.wood)
        val sword = BlockIslandRules.act(axe, "craft_sword")
        assertTrue("sword" in sword.tools)
        val fruit = sword.copy(fruit = 1)
        val fed = BlockIslandRules.act(fruit, "eat")
        assertEquals(9, fed.world.hp)
        assertEquals(0, fed.fruit)
        val reloaded = BlockIslandState.fromJson(fed.toJson())
        assertNotNull(reloaded)
        assertEquals(fed.tools, reloaded!!.tools)
        assertEquals(fed.world.tiles, reloaded.world.tiles)
        assertEquals(fed.world.wood, reloaded.world.wood)
        assertEquals(fed.structures, reloaded.structures)
        assertEquals(fed.world.friends, reloaded.world.friends)
    }

    @Test fun plantingRenewablyGrowsTreesButRequiresSeedResource() {
        val base = BlockIslandRules.newWorld(5, emptyList()).copy(
            world = BlockIslandRules.newWorld(5, emptyList()).world.copy(wood = 1, mobs = emptyList()),
        )
        val planted = BlockIslandRules.act(base, "plant", 5, 5)
        assertEquals(1, planted.world.cell(5, 5))
        assertEquals(0, planted.world.wood)
        val harvested = BlockIslandRules.act(planted, "gather", 5, 5)
        assertEquals(0, harvested.world.cell(5, 5))
        assertEquals(2, harvested.world.wood)
        val invalid = BlockIslandRules.act(base.copy(world = base.world.copy(wood = 0)), "plant", 5, 5)
        assertEquals(0, invalid.world.cell(5, 5))
        assertEquals(0, invalid.world.wood)
    }

    @Test fun wallsAndFloorsCanBeRemovedWithoutDuplicatingMaterials() {
        val initial = BlockIslandRules.newWorld(44, emptyList())
            .let { it.copy(world = it.world.copy(wood = 5, mobs = emptyList())) }
        val floor = BlockIslandRules.act(initial, "floor", 5, 5)
        assertEquals(4, floor.world.cell(5, 5))
        val duplicate = BlockIslandRules.act(floor, "floor", 5, 5)
        assertEquals(floor.world.wood, duplicate.world.wood)
        assertEquals(floor.world.turns, duplicate.world.turns)
        val wall = BlockIslandRules.act(initial, "wall", 5, 5)
        assertEquals(3, wall.world.cell(5, 5))
        assertEquals(3, wall.world.wood)
        val removed = BlockIslandRules.act(wall, "remove", 5, 5)
        assertEquals(0, removed.world.cell(5, 5))
        assertEquals(4, removed.world.wood)
        val noDupe = BlockIslandRules.act(removed, "remove", 5, 5)
        assertEquals(4, noDupe.world.wood)
    }

    @Test fun actualTeammateGatheringAndOrdersArePersisted() {
        val initial = BlockIslandRules.newWorld(77, listOf("a"))
        val changed = initial.world.tiles.toMutableList().also { it[initial.world.index(6, 4)] = 1 }
        val custom = initial.copy(world = initial.world.copy(
            tiles = changed, mobs = emptyList(),
            friends = listOf(ExpeditionFriend("a", 5, 4, mode = "gather")),
        ))
        val operated = BlockIslandRules.act(custom, "move", 4, 6)
        assertEquals(0, operated.world.cell(6, 4))
        assertTrue(operated.world.wood >= 1)
        assertTrue(operated.world.events.any { it.contains("队友[a]") })
        val guards = BlockIslandRules.setCompanionMode(operated, "a", "guard")
        assertEquals("guard", guards.world.friends.single().mode)
        val reread = BlockIslandState.fromJson(guards.toJson())!!
        assertEquals("guard", reread.world.friends.single().mode)
        val solo = with(BlockIslandRules) { reread.withPartners(emptyList()) }
        assertTrue(solo.world.friends.isEmpty())
    }

    @Test fun enemyOccupationNeverAllowsAPathThroughEnemy() {
        val base = BlockIslandRules.newWorld(72, emptyList())
            .let { it.copy(world = it.world.copy(mobs = listOf(ExpeditionMob(12, 6, 5, 4)))) }
        val step = BlockIslandRules.nextStep(base, 4, 5, 6, 5)
        assertNotNull(step)
        assertEquals(5 to 5, step)
        assertNull(BlockIslandRules.nextStep(base, 4, 5, -1, 5))
        val attackAtRange = BlockIslandRules.act(base, "fight", 6, 5)
        assertEquals(base.world.turns, attackAtRange.world.turns)
    }


    @Test fun bedsRestoreHealthAndKeepARealRestCooldown() {
        val initial = BlockIslandRules.newWorld(5, emptyList())
            .let { it.copy(world = it.world.copy(wood = 4, stone = 2, hp = 4, mobs = emptyList())) }
        val bed = BlockIslandRules.act(initial, "bed", 5, 5)
        assertEquals("bed", bed.structures[bed.world.index(5, 5)])
        assertFalse(BlockIslandRules.isWalkable(bed, 5, 5))
        val rested = BlockIslandRules.act(bed, "rest")
        assertEquals(8, rested.world.hp)
        val refused = BlockIslandRules.act(rested, "rest")
        assertEquals(rested.world.hp, refused.world.hp)
        assertEquals(rested.world.turns, refused.world.turns)
        assertEquals(rested.structures, BlockIslandState.fromJson(rested.toJson())!!.structures)
    }

    @Test fun fallenCompanionNeedsTwoFruitsAndActuallyGetsRescued() {
        val initial = BlockIslandRules.newWorld(19, listOf("friend"))
            .let { it.copy(world = it.world.copy(mobs = emptyList(), friends =
                listOf(ExpeditionFriend("friend", 5, 5, 0))), fruit = 2) }
        val revived = BlockIslandRules.act(initial, "revive", 5, 5)
        assertEquals(0, revived.fruit)
        assertEquals(4, revived.world.friends.single().hp)
        assertTrue(revived.world.events.any { it.contains("救起了队友[friend]") })
        val repeat = BlockIslandRules.act(revived, "revive", 5, 5)
        assertEquals(revived.world.friends.single().hp, repeat.world.friends.single().hp)
        val lacking = BlockIslandRules.act(initial.copy(fruit = 1), "revive", 5, 5)
        assertEquals(0, lacking.world.friends.single().hp)
    }

    @Test fun longWorldAddsResourcesWithoutDestroyingBuildings() {
        val initial = BlockIslandRules.newWorld(42, emptyList())
            .let { it.copy(world = it.world.copy(turns = 27, mobs = emptyList(), wood = 4, stone = 2)) }
        val workshop = BlockIslandRules.act(initial, "workbench", 5, 5)
        assertEquals("workbench", workshop.structures[workshop.world.index(5, 5)])
        assertEquals(0, workshop.world.cell(5, 5))
        assertTrue(workshop.world.events.any { it.contains("新的") })
    }

    @Test fun woodenBridgeIsPassableAndCannotBeDemolishedUnderAnOccupant() {
        val baseline = BlockIslandRules.newWorld(2026, emptyList())
        val tiles = baseline.world.tiles.toMutableList()
        tiles[baseline.world.index(5, 5)] = 8
        val start = baseline.copy(world = baseline.world.copy(tiles = tiles, wood = 6, mobs = emptyList()))
        assertFalse(BlockIslandRules.isWalkable(start, 5, 5))
        val bridged = BlockIslandRules.act(start, "bridge", 5, 5)
        assertEquals("bridge", bridged.structures[bridged.world.index(5, 5)])
        assertEquals(3, bridged.world.wood)
        assertEquals(8, bridged.world.cell(5, 5))
        assertTrue(BlockIslandRules.isWalkable(bridged, 5, 5))
        val stepped = BlockIslandRules.act(bridged, "move", 5, 5)
        assertEquals(5, stepped.world.x)
        val blocked = BlockIslandRules.act(stepped, "remove", 5, 5)
        assertEquals(stepped.world.turns, blocked.world.turns)
        assertEquals("bridge", blocked.structures[stepped.world.index(5, 5)])
        val returned = BlockIslandRules.act(stepped, "move", 4, 5)
        val removed = BlockIslandRules.act(returned, "remove", 5, 5)
        assertEquals(8, removed.world.cell(5, 5))
        assertFalse(BlockIslandRules.isWalkable(removed, 5, 5))
        assertEquals(5, removed.world.wood)
        assertEquals(removed.structures, BlockIslandState.fromJson(removed.toJson())!!.structures)
    }

    @Test fun fishingRequiresARealRodAndCookingNeedsCampfire() {
        val baseline = BlockIslandRules.newWorld(27, emptyList())
        val tiles = baseline.world.tiles.toMutableList()
        tiles[baseline.world.index(5, 5)] = 8
        val start = baseline.copy(world = baseline.world.copy(tiles = tiles, mobs = emptyList(), hp = 5))
        val withoutRod = BlockIslandRules.act(start, "fish", 5, 5)
        assertEquals(start.world.turns, withoutRod.world.turns)
        val withRod = start.copy(tools = setOf("rod"), fish = 1)
        val caughtOrMissed = BlockIslandRules.act(withRod, "fish", 5, 5)
        assertEquals(withRod.world.turns + 1, caughtOrMissed.world.turns)
        val tooEarly = BlockIslandRules.act(caughtOrMissed, "fish", 5, 5)
        assertEquals(caughtOrMissed.world.turns, tooEarly.world.turns)
        val noCampfire = BlockIslandRules.act(withRod, "eat_fish")
        assertEquals(5, noCampfire.world.hp)
        val withFire = withRod.copy(structures = mapOf(withRod.world.index(5, 4) to "campfire"))
        val cooked = BlockIslandRules.act(withFire, "eat_fish")
        assertEquals(10, cooked.world.hp)
        assertEquals(0, cooked.fish)
        val restored = BlockIslandState.fromJson(cooked.toJson())!!
        assertEquals(0, restored.fish)
        assertEquals(caughtOrMissed.lastFishTurn,
            BlockIslandState.fromJson(caughtOrMissed.toJson())!!.lastFishTurn)
    }

    @Test fun olderIslandSaveMigratesWithoutDeletingBuildingsOrHistory() {
        val old = ExpeditionRules.start(ExpeditionMode.HOMESTEAD, 315, listOf("oldRole"))
            .copy(wood = 7, stone = 4, turns = 19, events = listOf("旧小屋完成"))
        val imported = BlockIslandState.fromLegacy(old)!!
        assertEquals(7, imported.world.wood)
        assertEquals(19, imported.world.turns)
        assertEquals(listOf("旧小屋完成"), imported.world.events)
        val restored = BlockIslandState.fromJson(imported.toJson())!!
        assertEquals(old.tiles, restored.world.tiles)
        assertEquals(old.friends, restored.world.friends)
        assertEquals(old.events, restored.world.events)
        assertNull(BlockIslandState.fromLegacy(ExpeditionRules.start(ExpeditionMode.RELIC_RUN, 315, emptyList())))
    }
}
