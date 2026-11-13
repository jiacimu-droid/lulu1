package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandProgressionTest {
    private fun island(seed: Int = 42): BlockIslandState {
        val old=BlockIslandRules.newWorld(seed, emptyList())
        return old.copy(animals=emptyList(),supplyCaches=emptyList(),
            world=old.world.copy(wood=20,stone=20,hp=12,mobs=emptyList()))
    }

    @Test fun rareBlueOreCannotBeConsumedByBareHandsAndPickaxeGivesRealCrystal() {
        val index=island().world.index(5,5)
        val seed=(1..100).first { IslandProgression.crystalVein(it,index) }
        val base=island(seed)
        val tiles=base.world.tiles.toMutableList().also { it[index]=2 }
        val ore=base.copy(world=base.world.copy(tiles=tiles))
        val without=BlockIslandRules.act(ore,"gather",5,5)
        assertEquals(ore.world.turns,without.world.turns)
        assertEquals(2,without.world.cell(5,5))
        assertEquals(0,without.crystals)
        assertFalse(islandTargetAction(ore,5 to 5).enabled)
        assertTrue(islandTargetAction(ore,5 to 5).label.contains("石镐"))

        val equipped=ore.copy(tools=setOf("pickaxe"))
        assertTrue(islandTargetAction(equipped,5 to 5).enabled)
        val mined=BlockIslandRules.act(equipped,"gather",5,5)
        assertEquals(0,mined.world.cell(5,5))
        assertEquals(24,mined.world.stone)
        assertEquals(1,mined.crystals)
        assertEquals(mined.crystals,BlockIslandState.fromJson(mined.toJson())!!.crystals)
        val again=BlockIslandRules.act(mined,"gather",5,5)
        assertEquals(mined.world.turns,again.world.turns)
        assertEquals(mined.crystals,again.crystals)

        val normalSeed=(1..100).first { !IslandProgression.crystalVein(it,index) }
        val plain=island(normalSeed)
        val simple=plain.copy(world=plain.world.copy(
            tiles=plain.world.tiles.toMutableList().also {it[index]=2}))
        val bare=BlockIslandRules.act(simple,"gather",5,5)
        assertEquals(22,bare.world.stone)
        assertEquals(0,bare.crystals)
    }

    @Test fun crystalLampNeedsMiningResourceAndActuallyBlocksNightDamage() {
        val base=island().copy(crystals=0)
        assertTrue(BlockIslandRules.canPlaceBuilding(base,"crystal_lantern",5,5))
        assertFalse(BlockIslandRules.canBuild(base,"crystal_lantern",5,5))
        val denied=BlockIslandRules.act(base,"crystal_lantern",5,5)
        assertEquals(base.world.turns,denied.world.turns)
        val stocked=base.copy(crystals=1)
        val built=BlockIslandRules.act(stocked,"crystal_lantern",5,5)
        assertEquals(0,built.crystals)
        assertEquals(17,built.world.wood)
        assertEquals(18,built.world.stone)
        assertEquals("crystal_lantern",built.structures[built.world.index(5,5)])
        assertFalse(BlockIslandRules.isWalkable(built,5,5))
        assertEquals(built.structures,BlockIslandState.fromJson(built.toJson())!!.structures)
        val homeChallenge=IslandChallengeBoard.challenges.first { it.id=="home" }
        assertTrue(homeChallenge.completed(built.copy(
            structures=built.structures+(built.world.index(6,5) to "pet_house"))))
        val takenDown=BlockIslandRules.act(built.copy(world=built.world.copy(
            x=4,y=5,mobs=emptyList())),"remove",5,5)
        assertEquals(1,takenDown.crystals)
        assertFalse(takenDown.structures.containsKey(built.world.index(5,5)))
        val secondRemove=BlockIslandRules.act(takenDown,"remove",5,5)
        assertEquals(takenDown.crystals,secondRemove.crystals)
        assertEquals(takenDown.world.turns,secondRemove.world.turns)

        val w=built.world.copy(turns=29,x=4,y=5,hp=8,
            mobs=listOf(ExpeditionMob(12,4,4,4)))
        val lit=built.copy(world=w,fruit=1,hunger=8)
        val dark=lit.copy(structures=lit.structures.filterValues{it!="crystal_lantern"})
        val litNext=BlockIslandRules.act(lit,"eat")
        val darkNext=BlockIslandRules.act(dark,"eat")
        assertTrue(IslandProgression.crystalLampProtects(litNext))
        assertEquals(darkNext.world.hp+1,litNext.world.hp)
        assertEquals(litNext.world.turns,darkNext.world.turns)
    }

    @Test fun knockoutDropsRealSuppliesAndOnlyOneRecoveryReturnsThem() {
        val base=island()
        val loss=base.copy(fruit=6,crystals=4,
            world=base.world.copy(x=4,y=5,hp=1,turns=10,wood=10,stone=8,
                mobs=listOf(ExpeditionMob(10,4,3,4))))
        val knocked=BlockIslandRules.act(loss,"move",4,4)
        assertEquals(6,knocked.world.hp)
        assertEquals(4,knocked.world.x)
        assertEquals(5,knocked.world.y)
        assertEquals(5,knocked.world.wood)
        assertEquals(4,knocked.world.stone)
        assertEquals(3,knocked.fruit)
        assertEquals(2,knocked.crystals)
        val packKey=loss.world.index(4,4)
        val pack=knocked.lostPacks.getValue(packKey)
        assertEquals(IslandLostPack(5,4,3,2),pack)
        assertEquals(pack,BlockIslandState.fromJson(knocked.toJson())!!.lostPacks[packKey])
        assertEquals("recover_pack",islandTargetAction(knocked,4 to 4).action)
        val found=BlockIslandRules.act(knocked,"recover_pack",4,4)
        assertFalse(packKey in found.lostPacks)
        assertEquals(10,found.world.wood)
        assertEquals(8,found.world.stone)
        assertEquals(6,found.fruit)
        assertEquals(4,found.crystals)
        val repeat=BlockIslandRules.act(found,"recover_pack",4,4)
        assertEquals(found.world.turns,repeat.world.turns)
        assertEquals(found.crystals,repeat.crystals)
    }

    @Test fun craftedCharmIsConsumedOnceInsteadOfMintingFreeRevives() {
        val base=island()
        val attempt=base.copy(tools=setOf("axe","charm"),
            fruit=3,crystals=2,world=base.world.copy(
                x=4,y=5,hp=1,turns=10,wood=5,stone=3,
                mobs=listOf(ExpeditionMob(10,4,3,4))))
        val saved=BlockIslandRules.act(attempt,"move",4,4)
        assertEquals(3,saved.world.hp)
        assertFalse("charm" in saved.tools)
        assertTrue("axe" in saved.tools)
        assertTrue(saved.lostPacks.isEmpty())
        assertEquals(2,saved.crystals)
        assertEquals(4,saved.world.x)
        assertEquals(4,saved.world.y)
        assertTrue(saved.world.events.any { it.contains("护符碎裂") })
        assertEquals(saved.tools,BlockIslandState.fromJson(saved.toJson())!!.tools)
    }

    @Test fun oldSavesWithoutCrystalOrLostPackFieldsStillLoad() {
        val base=island()
        val old=org.json.JSONObject(base.toJson()).apply {
            remove("crystals")
            remove("lostPacks")
        }.toString()
        val restored=BlockIslandState.fromJson(old)!!
        assertEquals(0,restored.crystals)
        assertEquals(emptyMap<Int,IslandLostPack>(),restored.lostPacks)
    }

    @Test fun repeatedKnockoutAtSameTileMergesSavedPacksAndRecoveryIsIdempotent() {
        val base=island().copy(fruit=6,crystals=4)
        val deathTile=base.world.index(4,4)
        val existing=IslandLostPack(2,3,1,1)
        val exposed=base.copy(
            lostPacks=mapOf(deathTile to existing),
            world=base.world.copy(x=4,y=5,hp=1,turns=10,wood=10,stone=8,
                mobs=listOf(ExpeditionMob(10,4,3,4))))
        val knocked=BlockIslandRules.act(exposed,"move",4,4)
        assertEquals(IslandLostPack(7,7,4,3),knocked.lostPacks[deathTile])
        val reloaded=BlockIslandState.fromJson(knocked.toJson())!!
        assertEquals(knocked.lostPacks,reloaded.lostPacks)
        val recovered=BlockIslandRules.act(reloaded,"recover_pack",4,4)
        assertFalse(deathTile in recovered.lostPacks)
        assertEquals(12,recovered.world.wood)
        assertEquals(11,recovered.world.stone)
        assertEquals(7,recovered.fruit)
        assertEquals(5,recovered.crystals)
        val savedAgain=BlockIslandState.fromJson(recovered.toJson())!!
        val duplicate=BlockIslandRules.act(savedAgain,"recover_pack",4,4)
        assertEquals(savedAgain.world.turns,duplicate.world.turns)
        assertEquals(savedAgain.world.wood,duplicate.world.wood)
        assertEquals(savedAgain.crystals,duplicate.crystals)
    }

}
