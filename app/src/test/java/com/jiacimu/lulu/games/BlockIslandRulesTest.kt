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
    @Test fun explorationMilestonesReadPhysicalSaveInsteadOfResettingAfterThreeNights() {
        val base=BlockIslandRules.newWorld(311,emptyList())
        val table=base.world.index(5,5)
        val fire=base.world.index(5,6)
        val bridge=base.world.tiles.indices.first { index ->
            val x=index%base.world.width;val y=index/base.world.width
            base.world.tiles[index]==8 && x in 1 until base.world.width-1 &&
                y in 1 until base.world.height-1
        }
        val settled=base.copy(
            structures=mapOf(table to "workbench",fire to "campfire"),
            tools=setOf("sword"),nightsSurvived=3,defeatedMonsters=1,
        )
        assertTrue(IslandMilestones.next(settled).contains("探险 1/3"))
        val caches=settled.copy(openedSupplies=settled.supplyCaches.toSet())
        assertTrue(IslandMilestones.next(caches).contains("探险 2/3"))
        val crafted=caches.copy(tools=setOf("axe","pickaxe","sword","rod"))
        assertTrue(IslandMilestones.next(crafted).contains("探险 3/3"))
        val bridged=crafted.copy(structures=crafted.structures+(bridge to "bridge"))
        assertTrue(IslandMilestones.next(bridged).contains("生态收集"))
        val restored=BlockIslandState.fromJson(bridged.toJson())!!
        assertEquals(IslandMilestones.next(bridged),IslandMilestones.next(restored))
    }

    @Test fun generatedWildlifeIsStableReachableAndDoesNotReplaceBuildingsOnMigration() {
        val first=BlockIslandRules.newWorld(99,emptyList())
        val again=BlockIslandRules.newWorld(99,emptyList())
        assertEquals(first.animals,again.animals)
        assertEquals(IslandWildlife.species.toSet(),first.animals.map{it.species}.toSet())
        first.animals.forEach { animal ->
            assertTrue(BlockIslandRules.isWalkable(first,animal.x,animal.y))
            assertNotNull(BlockIslandRules.nextStep(first,first.world.x,first.world.y,animal.x,animal.y))
        }
        val constructed=first.copy(structures=mapOf(first.world.index(5,5) to "bed"))
        val oldJson=org.json.JSONObject(constructed.toJson()).apply {
            remove("animals");remove("discoveredSpecies")
        }
        val upgraded=BlockIslandState.fromJson(oldJson.toString())!!
        assertEquals(constructed.structures,upgraded.structures)
        assertTrue(upgraded.animals.none {
            upgraded.world.index(it.x,it.y) in constructed.structures
        })
        assertEquals(upgraded.animals,BlockIslandState.fromJson(upgraded.toJson())!!.animals)
    }

    @Test fun friendshipCostsRealFoodAndGiftsRequireTurnsWithNoRepeatedMint() {
        val island=BlockIslandRules.newWorld(123,emptyList())
        val species=listOf("hare" to (1 to 0), "duck" to (0 to 1), "fox" to (0 to 2))
        for((name,payment) in species) {
            val animal=island.animals.first{it.species==name}
            val start=island.copy(
                world=island.world.copy(x=animal.x,y=animal.y,mobs=emptyList()),
                fruit=payment.first,fish=payment.second,
            )
            val friendship=BlockIslandRules.act(start,"tame",animal.x,animal.y)
            assertTrue(friendship.animals.first{it.id==animal.id}.befriended)
            assertEquals(payment.first-if(name=="hare")1 else 0,friendship.fruit)
            assertEquals(payment.second-if(name=="duck")1 else if(name=="fox")2 else 0,friendship.fish)
            val noDouble=BlockIslandRules.act(friendship,"tame",animal.x,animal.y)
            assertEquals(friendship.world.turns,noDouble.world.turns)
            val current=friendship.animals.first{it.id==animal.id}
            val due=friendship.copy(
                world=friendship.world.copy(x=current.x,y=current.y,turns=current.lastGiftTurn+25))
            val gifted=BlockIslandRules.act(due,"care",current.x,current.y)
            assertEquals(due.world.turns+1,gifted.world.turns)
            assertEquals(1,when(name) {
                "hare"->gifted.fruit-due.fruit
                "duck"->gifted.fish-due.fish
                else->(gifted.world.stone-due.world.stone)/2
            })
            val giftState=gifted.animals.first{it.id==animal.id}
            val repeat=BlockIslandRules.act(
                gifted.copy(world=gifted.world.copy(x=giftState.x,y=giftState.y)),
                "care",giftState.x,giftState.y)
            assertEquals(gifted.world.turns,repeat.world.turns)
            assertEquals(gifted.animals,BlockIslandState.fromJson(gifted.toJson())!!.animals)
        }
    }

    @Test fun wildAnimalDoesNotProvideFreeFoodBeforeFriendship() {
        val island=BlockIslandRules.newWorld(311,emptyList())
        val animal=island.animals.first()
        val nearby=island.copy(world=island.world.copy(x=animal.x,y=animal.y,mobs=emptyList()))
        assertFalse(IslandWildlife.canGift(nearby,animal))
        val refused=BlockIslandRules.act(nearby,"tame",animal.x,animal.y)
        assertEquals(nearby.world.turns,refused.world.turns)
        assertEquals(nearby.fruit,refused.fruit)
        assertTrue(refused.animals.none{it.befriended})
    }

    @Test fun wildlifeCannotBeBuriedByBuildingPlantingOrCompanionActions() {
        val base=BlockIslandRules.newWorld(101,listOf("friend"))
        val target=5 to 5
        val animal=base.animals.first().copy(x=target.first,y=target.second)
        val occupied=base.copy(animals=listOf(animal),
            world=base.world.copy(wood=20,stone=20,mobs=emptyList()))
        assertFalse(BlockIslandRules.canPlaceBuilding(occupied,"wall",target.first,target.second))
        assertFalse(BlockIslandRules.canPlantAt(occupied,target.first,target.second))
        assertEquals(occupied.world.turns,
            BlockIslandRules.act(occupied,"wall",target.first,target.second).world.turns)
        assertEquals(occupied.world.turns,
            BlockIslandRules.act(occupied,"plant",target.first,target.second).world.turns)
        val choices=IslandActorPlay.options(occupied,"friend")
        assertFalse(choices.any {
            it.kind in setOf("plant","floor","wall") &&
                it.x==target.first && it.y==target.second
        })
        val stored=BlockIslandState.fromJson(occupied.toJson())!!
        assertEquals(occupied.animals,stored.animals)
    }

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

    @Test fun buildingAvailabilityUsesTheSamePlacementAndResourceRulesAsExecution() {
        val created = BlockIslandRules.newWorld(6, emptyList())
        val stocked = created.copy(world = created.world.copy(wood = 6, stone = 2))
        assertTrue(BlockIslandRules.canPlaceBuilding(stocked, "wall", 5, 5))
        assertTrue(BlockIslandRules.canBuild(stocked, "wall", 5, 5))
        assertTrue(BlockIslandRules.canBuild(stocked, "workbench", 5, 5))
        assertFalse(BlockIslandRules.canBuild(stocked, "wall", 4, 5))
        assertFalse(BlockIslandRules.canBuild(stocked, "wall", -1, 5))
        assertFalse(BlockIslandRules.canBuild(stocked, "unknown", 5, 5))

        val poor = stocked.copy(world = stocked.world.copy(wood = 1, stone = 0))
        assertTrue(BlockIslandRules.canPlaceBuilding(poor, "wall", 5, 5))
        assertFalse(BlockIslandRules.canBuild(poor, "wall", 5, 5))
        assertFalse(BlockIslandRules.canBuild(poor, "workbench", 5, 5))

        val walled = BlockIslandRules.act(stocked, "wall", 5, 5)
        assertFalse(BlockIslandRules.canBuild(walled, "wall", 5, 5))
        assertTrue(BlockIslandRules.canBuild(walled, "door", 5, 5))
        val doored = BlockIslandRules.act(walled, "door", 5, 5)
        assertEquals("door", doored.structures[doored.world.index(5, 5)])
        assertFalse(BlockIslandRules.canBuild(doored, "wall", 5, 5))
        assertFalse(BlockIslandRules.canBuild(doored, "door", 5, 5))
        assertTrue(BlockIslandRules.canRemoveAt(doored, 5, 5))
        assertFalse(BlockIslandRules.canPlantAt(doored, 5, 5))
        val cleared = BlockIslandRules.act(doored, "remove", 5, 5)
        assertFalse(BlockIslandRules.canRemoveAt(cleared, 5, 5))
        assertTrue(BlockIslandRules.canPlantAt(cleared, 5, 5))
        assertFalse(BlockIslandRules.canPlantAt(cleared, cleared.world.x, cleared.world.y))
        assertFalse(BlockIslandRules.canRemoveAt(cleared, -1, 5))
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

    @Test fun nonLethalSwordAndFistAttacksReportActualDamage() {
        val initial = BlockIslandRules.newWorld(613, emptyList())
        val withMob = initial.copy(world = initial.world.copy(
            mobs = listOf(ExpeditionMob(200, 5, 5, 9)),
        ))
        val fists = BlockIslandRules.act(withMob, "fight", 5, 5)
        assertTrue(fists.world.events.any { it == "攻击怪物，造成2点伤害" })
        assertEquals(7, fists.world.mobs.single { it.id == 200 }.hp)

        val sword = BlockIslandRules.act(withMob.copy(tools = setOf("sword")), "fight", 5, 5)
        assertTrue(sword.world.events.any { it == "攻击怪物，造成4点伤害" })
        assertEquals(5, sword.world.mobs.single { it.id == 200 }.hp)
        assertEquals(withMob.world.turns + 1, sword.world.turns)
    }

    @Test fun playerCannotStandOnTeammateAndAutoPathDetoursAroundThem() {
        val base = BlockIslandRules.newWorld(113, listOf("friend"))
        val withFriendBlocking = base.copy(world = base.world.copy(
            mobs = emptyList(),
            friends = listOf(ExpeditionFriend("friend", 5, 5)),
        ))
        val refused = BlockIslandRules.act(withFriendBlocking, "move", 5, 5)
        assertEquals(withFriendBlocking.world.turns, refused.world.turns)
        assertEquals(withFriendBlocking.world.x to withFriendBlocking.world.y,
            refused.world.x to refused.world.y)
        assertEquals(withFriendBlocking.world.friends, refused.world.friends)
        var exploring = withFriendBlocking
        var moves = 0
        while (exploring.world.x to exploring.world.y != (6 to 5) && moves < 10) {
            val step = BlockIslandRules.nextStep(exploring, exploring.world.x, exploring.world.y, 6, 5)
            assertNotNull("A valid detour should exist", step)
            assertNotEquals("Never plan a step through a teammate", 5 to 5, step)
            exploring = exploring.copy(world = exploring.world.copy(x = step!!.first, y = step.second))
            moves++
        }
        assertEquals(6 to 5, exploring.world.x to exploring.world.y)
        assertTrue(moves >= 4)
    }

    @Test fun downedAllyRemainsPassableForRescueRangeButNeverOverlaps() {
        val base = BlockIslandRules.newWorld(113, listOf("friend"))
        val occupied = base.copy(world = base.world.copy(
            mobs = emptyList(),
            friends = listOf(ExpeditionFriend("friend", 5, 5, hp = 0)),
        ))
        assertNull(BlockIslandRules.nextStep(occupied, 4, 5, 5, 5))
        assertEquals(occupied.world.turns, BlockIslandRules.act(occupied, "move", 5, 5).world.turns)
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

    @Test fun foodHungerAndCampNightRewardAreRealPersistedState() {
        val start = BlockIslandRules.newWorld(1101, emptyList())
        val ready = start.copy(
            world = start.world.copy(turns = 47, hp = 8, mobs = emptyList()),
            fruit = 1, hunger = 6,
            structures = mapOf(start.world.index(5, 5) to "campfire"),
        )
        val night = BlockIslandRules.act(ready, "eat")
        assertEquals(48, night.world.turns)
        assertEquals(9, night.hunger) // food +4, one hunger tick at dawn
        assertEquals(1, night.nightsSurvived)
        assertEquals(2, night.fruit) // one eaten, two earned for defending camp
        assertEquals(ready.world.stone + 1, night.world.stone)
        val reread = BlockIslandState.fromJson(night.toJson())!!
        assertEquals(9, reread.hunger)
        assertEquals(1, reread.nightsSurvived)
        assertEquals(0, reread.defeatedMonsters)
        val next = BlockIslandRules.act(reread, "eat")
        assertEquals(1, next.nightsSurvived) // no duplicate same-day reward

        val unsheltered = BlockIslandRules.act(ready.copy(structures = emptyMap()), "eat")
        assertEquals(0, unsheltered.nightsSurvived)
        assertEquals(0, unsheltered.fruit)
    }

    @Test fun monsterDefenseGivesFoodAndARecordedMilestone() {
        val start = BlockIslandRules.newWorld(1102, emptyList())
        val atRisk = start.copy(world = start.world.copy(
            mobs = listOf(ExpeditionMob(97, 5, 5, hp = 2)),
        ))
        val won = BlockIslandRules.act(atRisk, "fight", 5, 5)
        assertEquals(1, won.defeatedMonsters)
        assertEquals(2, won.fruit)
        val saved = BlockIslandState.fromJson(won.toJson())!!
        assertEquals(1, saved.defeatedMonsters)
        assertEquals(2, saved.fruit)
    }

    @Test fun thirdDefendedNightAwardsBuildMaterialsExactlyOnce() {
        val start = BlockIslandRules.newWorld(3131, emptyList())
        val ready = start.copy(world = start.world.copy(
            turns = 47, wood = 1, stone = 0, mobs = emptyList(), hp = 12),
            fruit = 1, hunger = 9, nightsSurvived = 2,
            structures = mapOf(start.world.index(5, 5) to "campfire"),
        )
        val won = BlockIslandRules.act(ready, "eat")
        assertEquals(3, won.nightsSurvived)
        assertEquals(9, won.world.wood)
        assertEquals(6, won.world.stone)
        assertTrue(won.world.events.any { it.contains("第三夜守岛挑战完成") })
        val next = BlockIslandRules.act(won, "eat")
        assertEquals(3, next.nightsSurvived)
        assertEquals(9, next.world.wood)
        assertEquals(6, next.world.stone)
    }
}
