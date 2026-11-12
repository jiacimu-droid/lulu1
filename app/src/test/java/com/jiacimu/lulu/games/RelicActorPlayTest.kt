package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class RelicActorPlayTest {
    private fun run(): RelicRun {
        val s = RelicEscapeWorld.create(77,1,listOf("alice","bob"),1,1)
        return s.copy(tiles=List(s.w*s.h) { 0 },mobs=emptyList(),sites=emptyList(),
            chests=emptyList(),seen=s.tiles.indices.toSet())
    }

    @Test fun ownMovementDoesNotMovePlayerOrAdvanceEnemyClock() {
        val s=run()
        val move=RelicActorPlay.options(s,"alice").first { it.kind=="move" }
        val next=RelicActorPlay.apply(s,"alice",IslandActorChoice(move.id,"我去前面看看"))!!
        assertEquals(move.x,next.allies.first().x)
        assertEquals(move.y,next.allies.first().y)
        assertEquals(s.hero,next.hero)
        assertEquals(s.turns,next.turns)
        assertEquals(s.allies[1],next.allies[1])
        assertEquals("我去前面看看",next.events.last().text)
        assertNotNull(RelicRun.read(next.json()))
    }

    @Test fun modelAndFixedFollowerCannotBothActInSameTurn() {
        val s=run()
        val next=RelicEscapeRules.act(s,"wait",autonomousIds=setOf("alice","bob"))
        assertEquals(s.allies,next.allies)
        assertTrue(next.events.none { it.who=="alice" || it.who=="bob" })
        val move=RelicActorPlay.options(next,"alice").first { it.kind=="move" }
        val acted=RelicActorPlay.apply(next,"alice",IslandActorChoice(move.id,""))!!
        assertNull(RelicActorPlay.apply(acted,"alice",IslandActorChoice(move.id,"")))
        // Physical moves are exhausted, but changing the next goal is a
        // conversation-level choice and never grants a second movement.
        val followUp = RelicActorPlay.options(acted,"alice")
        assertTrue(followUp.any { it.id=="none" })
        assertTrue(followUp.any { it.id=="plan_regroup" })
        assertTrue(followUp.none { it.kind in setOf(
            "move","attack","open","heal","rescue","share",
            "guard_player","regroup","support","approach_chest","rush_rescue") })
        assertTrue(RelicActorPlay.acted(RelicRun.read(acted.json())!!,"alice"))
    }

    @Test fun characterChoosesToCarryRealTreasureToPlayerAcrossSeveralTurns() {
        val treasure=RelicLoot("from-companion","幽蓝宝石",RelicTier.RARE,220)
        val base=run().copy(allies=run().allies.map { if(it.id=="alice")
            it.copy(x=8,y=8,bag=listOf(treasure)) else it })
        val option=RelicActorPlay.options(base,"alice")
            .first { it.id=="plan_deliver:from-companion" }
        val chosen=RelicActorPlay.apply(base,"alice",
            IslandActorChoice(option.id,"我找到好东西啦，给你送过去"))!!
        assertTrue(chosen.hero.bag.isEmpty())
        assertEquals("deliver",chosen.coopPlans["alice"]?.kind)
        assertEquals("from-companion",chosen.coopPlans["alice"]?.itemId)
        val restored=RelicRun.read(chosen.json())!!
        assertEquals(chosen.coopPlans,restored.coopPlans)
        var s=restored
        repeat(20) {
            s=RelicEscapeRules.act(s,"wait",autonomousIds=setOf("alice","bob"))
        }
        assertEquals(listOf(treasure),s.hero.bag)
        assertTrue(s.allies.first { it.id=="alice" }.bag.isEmpty())
        assertTrue("对方真正走到身边交付",s.events.any {
            it.who=="alice" && it.action=="share" && it.text.contains(treasure.name) })
        assertEquals(1,s.events.count { it.who=="alice" && it.action=="share" })
        assertFalse("送达后自动结束本次目标",s.coopPlans.containsKey("alice"))
    }

    @Test fun deliveryDetoursAroundStationaryTeammateWithoutInventingRewards() {
        val gift=RelicLoot("detour-gift","月银胸针",RelicTier.RARE,180)
        val start=run()
        val occupied=start.copy(hero=start.hero.copy(x=2,y=4),
            allies=start.allies.map { actor -> when(actor.id) {
                "alice"->actor.copy(x=4,y=4,bag=listOf(gift))
                "bob"->actor.copy(x=3,y=4)
                else->actor
            } })
        val chosen=RelicActorPlay.apply(occupied,"alice",
            IslandActorChoice("plan_deliver:detour-gift","亲自送过去"))!!
        val first=RelicEscapeRules.act(chosen,"wait",autonomousIds=setOf("alice","bob"))
        val moved=first.allies.first { it.id=="alice" }
        assertNotEquals("不能踏进队友占据的格子",3 to 4,moved.x to moved.y)
        assertEquals(listOf(gift),moved.bag)
        assertTrue(first.hero.bag.isEmpty())
        assertEquals("deliver",first.coopPlans["alice"]?.kind)
        var s=first
        repeat(8) {
            s=RelicEscapeRules.act(s,"wait",autonomousIds=setOf("alice","bob"))
        }
        assertEquals(listOf(gift),s.hero.bag)
        assertTrue(s.allies.first { it.id=="alice" }.bag.isEmpty())
        assertEquals(1,s.events.count { it.who=="alice" && it.action=="share" })
        assertFalse(s.coopPlans.containsKey("alice"))
        assertEquals(3 to 4,s.allies.first { it.id=="bob" }.let { it.x to it.y })
    }

    @Test fun treasureDeliveryRespectsFullBagAndExplicitCancellation() {
        val gift=RelicLoot("gift","月银胸针",RelicTier.RARE,180)
        val full=(0 until 8).map {
            RelicLoot("owned-"+it,"旧王银币",RelicTier.COMMON,60)
        }
        val base=run().copy(hero=run().hero.copy(bag=full),
            allies=run().allies.map { if(it.id=="alice")
                it.copy(bag=listOf(gift)) else it })
        val plan=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("plan_deliver:gift","拿给你"))!!
        assertTrue("选择路程不会凭空送宝",plan.hero.bag.none { it.id==gift.id })
        val next=RelicEscapeRules.act(plan,"wait",autonomousIds=setOf("alice","bob"))
        assertEquals(full,next.hero.bag)
        assertEquals(listOf(gift),next.allies.first().bag)
        assertTrue(next.events.any { it.who=="alice" && it.text.contains("背包满") })
        assertFalse(next.coopPlans.containsKey("alice"))

        val cancelled=RelicActorPlay.apply(plan,"alice",
            IslandActorChoice("stop_travel","先不送"))!!
        assertTrue(cancelled.coopPlans.isEmpty())
        val waited=RelicEscapeRules.act(cancelled,"wait",
            autonomousIds=setOf("alice","bob"))
        assertTrue(waited.hero.bag.none { it.id==gift.id })
        assertEquals(listOf(gift),waited.allies.first().bag)
    }

    @Test fun chestGoesToActualCompanionsBagOnceAndRespectsCapacity() {
        val s=run().copy(chests=listOf(RelicChest(4,4,2,RelicTier.RARE)))
        val choice=IslandActorChoice("open:4:2","")
        val next=RelicActorPlay.apply(s,"alice",choice)!!
        assertTrue(next.chests.single().opened)
        assertEquals(1,next.allies.first().bag.size)
        assertTrue(next.hero.bag.isEmpty())
        assertNull(RelicActorPlay.apply(next,"bob",choice))
        val full=s.copy(allies=s.allies.map { if(it.id=="alice")it.copy(bag=List(8){i->
            RelicLoot("$i","coin",RelicTier.COMMON,1) }) else it })
        assertNull(RelicActorPlay.apply(full,"alice",choice))
    }

    @Test fun hazardsAndRescueChangeRealHealthAndConsumeRealSupplies() {
        val s=run()
        val a=s.allies.first()
        val move=RelicActorPlay.options(s,"alice").first { it.kind=="move" }
        val trapped=s.copy(tiles=s.tiles.toMutableList().also { it[s.index(move.x,move.y)]=2 },
            sites=listOf(RelicSite(7,move.x,move.y,"trap")))
        val next=RelicActorPlay.apply(trapped,"alice",IslandActorChoice(move.id,""))!!
        assertEquals(a.hp-3,next.allies.first().hp)
        assertTrue(next.sites.single().spent)
        val down=s.copy(hero=s.hero.copy(hp=0),phase=RelicPhase.DOWNED)
        val rescued=RelicActorPlay.apply(down,"alice",IslandActorChoice("rescue","起来啦"))!!
        assertEquals(4,rescued.hero.hp)
        assertEquals(RelicPhase.EXPLORE,rescued.phase)
        assertEquals(a.kits-1,rescued.allies.first().kits)
        assertEquals(a.rescues+1,rescued.allies.first().rescues)
    }

    @Test fun companionSkillInterruptsRealWindupButDoesNotInventSecondAttack() {
        val base=run()
        val alice=base.allies.first { it.id=="alice" }
        val enemy=RelicMob(72,alice.x+1,alice.y,5,2,windup=true)
        val s=base.copy(mobs=listOf(enemy))
        assertTrue(RelicActorPlay.options(s,"alice").any { it.id=="interrupt:${enemy.x}:${enemy.y}" })
        val skill=RelicActorPlay.apply(s,"alice",
            IslandActorChoice("interrupt:${enemy.x}:${enemy.y}","我来牵制！"))!!
        assertEquals(4,skill.mobs.single().hp)
        assertFalse(skill.mobs.single().windup)
        assertEquals(s.turns,skill.turns)
        assertNull(RelicActorPlay.apply(skill,"alice",
            IslandActorChoice("attack:${enemy.x}:${enemy.y}","")))
        assertNull(RelicActorPlay.apply(s.copy(mobs=listOf(enemy.copy(windup=false))),
            "alice",IslandActorChoice("interrupt:${enemy.x}:${enemy.y}","")))
    }

    @Test fun teammateCombatKillLeavesPhysicalLootOnGroundAndCannotDoubleDrop() {
        val base=run()
        val alice=base.allies.first { it.id=="alice" }
        val enemy=RelicMob(91,alice.x+1,alice.y,2,2)
        val s=base.copy(mobs=listOf(enemy))
        val killed=RelicActorPlay.apply(s,"alice",
            IslandActorChoice("attack:${enemy.x}:${enemy.y}",""))!!
        assertEquals(0,killed.mobs.single().hp)
        assertEquals(1,killed.drops.size)
        assertEquals(s.id+":mob:91",killed.drops.single().loot.id)
        assertTrue(killed.drops.single().loot.value>=RelicTier.RARE.base)
        assertNull(RelicActorPlay.apply(killed,"alice",
            IslandActorChoice("attack:${enemy.x}:${enemy.y}","")))
        assertEquals(killed.drops,RelicRun.read(killed.json())!!.drops)
    }

    @Test fun aidSkillActuallySpendsOwnPotionToHealWoundedPlayer() {
        val base=run()
        val alice=base.allies.first { it.id=="alice" }
        val wounded=base.copy(hero=base.hero.copy(x=alice.x,y=alice.y,hp=3))
        assertTrue(RelicActorPlay.options(wounded,"alice").any { it.id=="aid_player" })
        val helped=RelicActorPlay.apply(wounded,"alice",
            IslandActorChoice("aid_player","给你药剂"))!!
        assertEquals(7,helped.hero.hp)
        assertEquals(alice.kits-1,helped.allies.first().kits)
        assertNull(RelicActorPlay.apply(helped,"alice",
            IslandActorChoice("aid_player","")))
        assertFalse(RelicActorPlay.options(wounded.copy(hero=wounded.hero.copy(hp=10)),
            "alice").any{it.id=="aid_player"})
    }

    @Test fun invalidStaleFinishedOrOtherActorChoicesNeverInventActions() {
        val s=run()
        assertNull(RelicActorPlay.apply(s,"unknown",IslandActorChoice("none","")))
        assertNull(RelicActorPlay.apply(s,"alice",IslandActorChoice("free-loot","")))
        assertNull(RelicActorPlay.apply(s.copy(phase=RelicPhase.SUCCESS),"alice",IslandActorChoice("none","")))
        val move=RelicActorPlay.options(s,"alice").first { it.kind=="move" }
        val blocked=s.copy(tiles=s.tiles.toMutableList().also { it[s.index(move.x,move.y)]=1 })
        assertNull(RelicActorPlay.apply(blocked,"alice",IslandActorChoice(move.id,"")))
    }

    @Test fun hiddenTreasureAndMonstersNeverEnterModelFacts() {
        val s=run().copy(chests=listOf(RelicChest(8,10,10,RelicTier.PRECIOUS)),
            mobs=listOf(RelicMob(9,10,11,5,1)),seen=setOf(0))
        val facts=org.json.JSONObject(RelicActorPlay.facts(s,"alice",""))
        assertEquals(0,facts.getJSONArray("chests").length())
        assertEquals(0,facts.getJSONArray("mobs").length())
        assertEquals(1,facts.getJSONArray("map").length())
    }
}
