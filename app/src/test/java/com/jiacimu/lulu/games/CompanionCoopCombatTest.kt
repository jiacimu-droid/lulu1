package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CompanionCoopCombatTest {
    private fun relic(): RelicRun {
        val base=RelicEscapeWorld.create(31415,1,listOf("alice"),1,1)
        val tiles=List(base.w*base.h) { i ->
            if(i%base.w==0 || i%base.w==base.w-1 ||
                i/base.w==0 || i/base.w==base.h-1)1 else 0
        }
        return base.copy(tiles=tiles,seen=tiles.indices.toSet(),
            mobs=emptyList(),sites=emptyList(),chests=emptyList(),
            allies=listOf(RelicActor("alice",3,2,hp=10,kits=1)))
    }

    @Test fun teammateMarksChestForPlayerAndCannotStealIt() {
        val w=relic()
        val chest=RelicChest(0,4,2,RelicTier.RARE)
        val prepared=w.copy(chests=listOf(chest),
            hero=w.hero.copy(x=4,y=3))
        val option=RelicActorPlay.options(prepared,"alice")
            .firstOrNull { it.id=="reserve:0" }
        assertNotNull(option)
        val chosen=RelicActorPlay.apply(prepared,"alice",
            IslandActorChoice("reserve:0","这箱给你开！"))!!
        assertTrue(0 in chosen.playerChestMarks)
        assertTrue(RelicActorPlay.options(chosen.copy(
            events=emptyList(),turns=chosen.turns+1),"alice")
            .none { it.id=="open:4:2" })
        val opened=RelicEscapeRules.act(chosen,"open",4,2,
            autonomousIds=setOf("alice"))
        assertTrue(opened.chests.first().opened)
        assertEquals(1,opened.hero.bag.size)
        assertEquals(0,opened.allies.first().bag.size)
        val restored=RelicRun.read(JSONObject(opened.json().toString()))!!
        assertEquals(setOf(0),restored.playerChestMarks)
    }

    @Test fun teammateReallyTransfersLootWithoutDuplicatingOrExceedingBagCapacity() {
        val loot=RelicLoot("treasure-1","珍珠",RelicTier.RARE,125)
        val w=relic().copy(allies=listOf(RelicActor("alice",3,2,bag=listOf(loot))))
        val offered=RelicActorPlay.options(w,"alice").first { it.id=="share:treasure-1" }
        val next=RelicActorPlay.apply(w,"alice",IslandActorChoice(offered.id,"你拿着！"))!!
        assertEquals(listOf(loot),next.hero.bag)
        assertTrue(next.allies.first().bag.isEmpty())
        assertEquals(125,next.value)
        assertNull(RelicActorPlay.apply(next,"alice",IslandActorChoice(offered.id,"")))
        val restored=RelicRun.read(JSONObject(next.json().toString()))!!
        assertEquals(125,restored.value)
    }

    @Test fun companionProtectsPlayerByAbsorbingRealHitExactlyOnce() {
        val w=relic().copy(mobs=listOf(
            RelicMob(11,2,3,10,kind=2,alert=4,windup=true)),
            allies=listOf(RelicActor("alice",3,2,hp=9)))
        val guard=RelicActorPlay.apply(w,"alice",
            IslandActorChoice("guard_player","我护着你！"))!!
        assertEquals("alice",guard.guardedBy)
        val defended=RelicEscapeRules.act(guard,"wait",autonomousIds=setOf("alice"))
        assertEquals(w.hero.hp,defended.hero.hp)
        assertEquals(8,defended.allies.first().hp)
        assertEquals("",defended.guardedBy)
        assertTrue(defended.events.any { it.text.contains("挡在玩家前面") })
        val second=defended.copy(mobs=defended.mobs.map { it.copy(windup=true) })
        val hit=RelicEscapeRules.act(second,"wait",autonomousIds=setOf("alice"))
        assertTrue(hit.hero.hp<second.hero.hp)
    }

    @Test fun companionCanChooseToMoveCloserToThreatWithoutTeleporting() {
        val w=relic().copy(mobs=listOf(RelicMob(6,6,2,7,0)))
        val option=RelicActorPlay.options(w,"alice").first { it.id=="support:6" }
        val next=RelicActorPlay.apply(w,"alice",IslandActorChoice(option.id,"我去包抄！"))!!
        val moved=next.allies.first()
        assertEquals(1,RelicEscapeRules.distance(3,2,moved.x,moved.y))
        assertEquals(w.hero,w.hero)
        assertEquals(w.mobs,next.mobs)
    }

    private fun island(): BlockIslandState {
        val original=BlockIslandRules.newWorld(16,listOf("alice"))
        val w=original.world
        return original.copy(supplyCaches=emptyList(),fruit=1,
            world=w.copy(tiles=List(w.width*w.height) { 0 },x=4,y=5,hp=8,
                friends=listOf(w.friends.first().copy(x=5,y=5,hp=8)),
                mobs=listOf(w.mobs.firstOrNull()?.copy(x=4,y=6,hp=8)
                    ?: throw AssertionError("new world lacks a monster"))))
    }

    @Test fun islandGuardReallyRedirectsOnePointOfDamage() {
        val w=island()
        val guard=IslandActorPlay.apply(w,"alice",w.world.turns,
            IslandActorChoice("guard_player","我在你旁边"))!!
        assertEquals("alice",guard.guardedBy)
        val result=BlockIslandRules.act(guard,"eat",4,5,autonomousIds=setOf("alice"))
        assertEquals(11,result.world.hp)
        assertEquals(7,result.world.friends.first().hp)
        assertEquals("",result.guardedBy)
        val restored=BlockIslandState.fromJson(guard.toJson())!!
        assertEquals("alice",restored.guardedBy)
    }

    @Test fun islandCompanionsChooseRealComboAttackNearPlayer() {
        val w=island().copy(world=island().world.copy(
            friends=island().world.friends.map { it.copy(x=5,y=6) }))
        assertTrue(IslandActorPlay.options(w,"alice").any { it.id=="combo:4:6" })
        val next=IslandActorPlay.apply(w,"alice",w.world.turns,
            IslandActorChoice("combo:4:6","我们一起上！"))!!
        assertEquals(5,next.world.mobs.first().hp)
        assertEquals(w.world.hp,next.world.hp)
        assertNull(IslandActorPlay.apply(next,"alice",next.world.turns,
            IslandActorChoice("combo:4:6","再打！")))
    }

    @Test fun legacyWorldRestoresWithoutTeamProtection() {
        val w=island()
        val json=JSONObject(w.toJson())
        json.remove("guardedBy")
        assertEquals("",BlockIslandState.fromJson(json.toString())!!.guardedBy)
        val r=relic()
        val saved=JSONObject(r.json().toString())
        saved.remove("playerChestMarks")
        saved.remove("guardedBy")
        val decoded=RelicRun.read(saved)!!
        assertTrue(decoded.playerChestMarks.isEmpty())
        assertEquals("",decoded.guardedBy)
    }
}
