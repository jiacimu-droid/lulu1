package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Regressions for model-selected routes; continuing a route must not decide a new goal. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class CoopRouteContinuationTest {
    private fun relic():RelicRun {
        val base=RelicEscapeWorld.create(319,1,listOf("alice"),1,1)
        val tiles=List(base.w*base.h) { i ->
            val x=i%base.w;val y=i/base.w
            if(x==0 || y==0 || x==base.w-1 || y==base.h-1)1 else 0
        }
        return base.copy(tiles=tiles,seen=tiles.indices.toSet(),
            hero=base.hero.copy(x=2,y=2),
            allies=listOf(RelicActor("alice",3,2)),
            mobs=emptyList(),chests=emptyList(),sites=emptyList(),events=emptyList())
    }

    private fun nextRelic(s:RelicRun):RelicRun =
        RelicCoopTravel.advance(s.copy(turns=s.turns+1),setOf("alice"))

    @Test fun selectedSupportChasesSameEnemyAcrossTurnsAndReentry() {
        val first=relic().copy(mobs=listOf(RelicMob(17,9,2,7,0)))
        val started=RelicActorPlay.apply(first,"alice",
            IslandActorChoice("support:17","我从这边去包抄"))!!
        assertEquals("support",started.coopPlans["alice"]?.kind)
        val loaded=RelicRun.read(JSONObject(started.json().toString()))!!
        assertEquals(started.coopPlans,loaded.coopPlans)
        var state=nextRelic(loaded.copy(mobs=listOf(RelicMob(17,9,3,7,0))))
        assertEquals("support",state.coopPlans["alice"]?.kind)
        assertTrue(RelicActorPlay.acted(state,"alice"))
        assertEquals(1,RelicEscapeRules.distance(
            started.allies.first().x,started.allies.first().y,
            state.allies.first().x,state.allies.first().y))
        repeat(15) { if(state.coopPlans.isNotEmpty())state=nextRelic(state) }
        assertTrue(state.coopPlans.isEmpty())
        assertEquals(1,RelicEscapeRules.distance(
            state.allies.first().x,state.allies.first().y,9,3))
        assertEquals(7,state.mobs.first().hp)
    }

    @Test fun markForPlayerImmediatelyCancelsOldTreasureRouteOnNextTurn() {
        val base=relic().copy(chests=listOf(RelicChest(3,9,2,RelicTier.RARE)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("approach_chest:3","我先去看看"))!!
        assertEquals("chest",started.coopPlans["alice"]?.kind)
        val reserved=RelicActorPlay.apply(started,"alice",
            IslandActorChoice("reserve:3","这宝箱让你开！"))!!
        assertEquals(setOf(3),reserved.playerChestMarks)
        val next=nextRelic(reserved)
        assertTrue(next.coopPlans.isEmpty())
        assertFalse(next.chests.first().opened)
        assertTrue(next.allies.first().bag.isEmpty())
    }

    @Test fun stopAfterSameTurnMoveDoesNotCreateAnExtraStep() {
        val base=relic().copy(mobs=listOf(RelicMob(9,9,2,7,0)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("support:9","我去帮你"))!!
        val current=started.allies.first()
        val stopped=RelicActorPlay.apply(started,"alice",
            IslandActorChoice("stop_travel","好，不打了"))!!
        assertTrue(stopped.coopPlans.isEmpty())
        assertEquals(current,stopped.allies.first())
        assertEquals(started.turns,stopped.turns)
        assertNull(RelicActorPlay.apply(stopped,"alice",
            IslandActorChoice("stop_travel","再停一次")))
    }

    @Test fun movementHazardsApplyToBothFirstAndFollowupStepsWithoutDuplication() {
        val base=relic().copy(mobs=listOf(RelicMob(12,9,2,8,0)),
            sites=listOf(RelicSite(5,4,2,"trap")))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("support:12","我去那边"))!!
        assertEquals(8,started.allies.first().hp)
        assertTrue(started.sites.first().spent)
        val later=nextRelic(started)
        assertEquals(8,later.allies.first().hp)
        assertEquals(5,later.allies.first().x)
    }

    @Test fun companionCanSwitchFromCombatToRegroupAfterItAlreadyMoved() {
        val base=relic().copy(mobs=listOf(RelicMob(9,9,2,7,0)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("support:9","我先去打怪"))!!
        val before=started.allies.first()
        assertTrue(RelicActorPlay.acted(started,"alice"))
        assertTrue(RelicActorPlay.options(started,"alice").any { it.id=="plan_regroup" })
        val switched=RelicActorPlay.apply(started,"alice",
            IslandActorChoice("plan_regroup","那我回来找你"))!!
        assertEquals("regroup",switched.coopPlans["alice"]?.kind)
        assertEquals(before,switched.allies.first())
        assertEquals(started.turns,switched.turns)
        val next=nextRelic(switched)
        assertEquals(1,RelicEscapeRules.distance(
            before.x,before.y,next.allies.first().x,next.allies.first().y))
        assertTrue(RelicEscapeRules.distance(next.allies.first().x,next.allies.first().y,
            next.hero.x,next.hero.y) <
            RelicEscapeRules.distance(before.x,before.y,next.hero.x,next.hero.y))
    }

    @Test fun midTurnRedirectionToChestDoesNotDuplicatePhysicalMove() {
        val base=relic().copy(mobs=listOf(RelicMob(9,8,2,7,0)),
            chests=listOf(RelicChest(2,9,4,RelicTier.RARE)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("support:9","我先去帮忙"))!!
        val originalPosition=started.allies.first()
        val redirect=RelicActorPlay.apply(started,"alice",
            IslandActorChoice("plan_chest:2","先去看看那个宝箱"))!!
        assertEquals(originalPosition,redirect.allies.first())
        assertEquals(started.turns,redirect.turns)
        assertEquals("chest",redirect.coopPlans["alice"]?.kind)
        assertEquals(2,redirect.coopPlans["alice"]?.targetId)
    }

    @Test fun oldRelicSaveAndInvalidPlansRestoreWithoutASecondWorld() {
        val base=relic()
        val raw=JSONObject(base.json().toString())
        raw.remove("coopPlans")
        assertTrue(RelicRun.read(raw)!!.coopPlans.isEmpty())
        raw.put("coopPlans",JSONObject().put("alice",
            JSONObject().put("kind","teleport").put("targetId",12).put("lastStepTurn",0)))
        val reread=RelicRun.read(raw)!!
        assertTrue(reread.coopPlans.isEmpty())
        assertEquals(base.tiles,reread.tiles)
    }

    @Test fun downedPlayerInterruptsOldRouteAndAllowsOwnRescueDecision() {
        val base=relic().copy(mobs=listOf(RelicMob(21,9,2,6,0)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("support:21","我去那边"))!!
        val actor=started.allies.first()
        val down=started.copy(hero=started.hero.copy(hp=0),
            phase=RelicPhase.DOWNED,turns=started.turns+1)
        val stopped=RelicCoopTravel.advance(down,setOf("alice"))
        assertTrue(stopped.coopPlans.isEmpty())
        assertEquals(actor,stopped.allies.first())
        assertFalse(RelicActorPlay.acted(stopped,"alice"))
        assertTrue(RelicActorPlay.options(stopped,"alice")
            .any { it.id=="rush_rescue" })
    }

    @Test fun dyingPlayerMayRedirectCompanionAfterItAlreadyMovedThisTurn() {
        val base=relic().copy(mobs=listOf(RelicMob(21,9,2,6,0)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("support:21","我去打怪"))!!
        val down=started.copy(hero=started.hero.copy(hp=0),
            phase=RelicPhase.DOWNED)
        assertTrue(RelicActorPlay.acted(down,"alice"))
        assertTrue(RelicActorPlay.options(down,"alice")
            .any { it.id=="plan_rescue" })
        val rerouted=RelicActorPlay.apply(down,"alice",
            IslandActorChoice("plan_rescue","我来救你"))!!
        assertEquals(started.allies.first(),rerouted.allies.first())
        assertEquals(started.turns,rerouted.turns)
        assertEquals("rescue",rerouted.coopPlans["alice"]?.kind)
        val restored=RelicRun.read(JSONObject(rerouted.json().toString()))!!
        assertEquals(rerouted.coopPlans,restored.coopPlans)
    }

    @Test fun roleApproachesDownedPlayerAndMustActuallySpendMedicine() {
        val base=relic().copy(hero=relic().hero.copy(hp=0),
            phase=RelicPhase.DOWNED,allies=listOf(RelicActor("alice",5,2,kits=1)))
        assertTrue(RelicActorPlay.options(base,"alice")
            .any { it.id=="rush_rescue" })
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("rush_rescue","我来啦"))!!
        assertEquals("rescue",started.coopPlans["alice"]?.kind)
        val approached=nextRelic(started)
        assertEquals(1,RelicEscapeRules.distance(approached.hero.x,approached.hero.y,
            approached.allies.first().x,approached.allies.first().y))
        assertEquals(0,approached.hero.hp)
        val ready=approached.copy(turns=approached.turns+1)
        assertTrue(RelicActorPlay.options(ready,"alice").any { it.id=="rescue" })
        val rescued=RelicActorPlay.apply(ready,"alice",
            IslandActorChoice("rescue","起来啦"))!!
        assertEquals(4,rescued.hero.hp)
        assertEquals(0,rescued.allies.first().kits)
        assertTrue(rescued.coopPlans.isEmpty())
    }

    @Test fun rescueTargetDisappearsAfterPlayerRecoveredWithoutFakingRescue() {
        val base=relic().copy(hero=relic().hero.copy(hp=0),
            phase=RelicPhase.DOWNED,allies=listOf(RelicActor("alice",5,2,kits=1)))
        val started=RelicActorPlay.apply(base,"alice",
            IslandActorChoice("rush_rescue","我来了"))!!
        val recovered=started.copy(hero=started.hero.copy(hp=4),
            phase=RelicPhase.EXPLORE)
        val result=nextRelic(recovered)
        assertTrue(result.coopPlans.isEmpty())
        assertEquals(recovered.allies.first(),result.allies.first())
        assertEquals(4,result.hero.hp)
    }

    private fun island():BlockIslandState {
        val base=BlockIslandRules.newWorld(27,listOf("alice"))
        val w=base.world
        return base.copy(supplyCaches=emptyList(),
            world=w.copy(tiles=List(w.width*w.height) { 0 },
                x=4,y=5,mobs=emptyList(),
                friends=listOf(w.friends.first().copy(x=12,y=5))))
    }

    private fun nextIsland(s:BlockIslandState):BlockIslandState {
        val updated=s.copy(world=s.world.copy(turns=s.world.turns+1))
        return IslandTravel.advance(updated,setOf("alice"))
    }

    @Test fun islandRegroupFollowsPlayersCurrentPosition() {
        val base=island()
        assertTrue(IslandActorPlay.options(base,"alice").any { it.id=="regroup" })
        val started=IslandActorPlay.apply(base,"alice",base.world.turns,
            IslandActorChoice("regroup","我过来陪你啦"))!!
        assertEquals(-2,started.companionTravel["alice"]?.targetMobId)
        val restored=BlockIslandState.fromJson(started.toJson())!!
        assertEquals(-2,restored.companionTravel["alice"]?.targetMobId)
        val moved=nextIsland(restored.copy(world=restored.world.copy(y=7)))
        assertNotNull(moved.companionTravel["alice"])
        assertEquals(1,kotlin.math.abs(started.world.friends.first().x-
            moved.world.friends.first().x)+kotlin.math.abs(started.world.friends.first().y-
            moved.world.friends.first().y))
        assertEquals(7,moved.world.y)
    }

    @Test fun islandCanSwitchToRegroupAfterAlreadyStartingCombatRoute() {
        val b=island()
        val mob=BlockIslandRules.newWorld(27,listOf("alice")).world.mobs.first()
        val s=b.copy(world=b.world.copy(mobs=listOf(mob.copy(id=91,x=17,y=5))))
        val started=IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice("approach:17:5","我去打怪"))!!
        val before=started.world.friends.first()
        assertTrue(IslandActorPlay.options(started,"alice",false)
            .any { it.id=="plan_regroup" })
        val changed=IslandActorPlay.apply(started,"alice",started.world.turns,
            IslandActorChoice("plan_regroup","等等，我先回你身边"))!!
        assertEquals(-2,changed.companionTravel["alice"]?.targetMobId)
        assertEquals(before,changed.world.friends.first())
        assertEquals(started.world.turns,changed.world.turns)
        val moved=nextIsland(changed)
        assertEquals(1,kotlin.math.abs(before.x-moved.world.friends.first().x)+
            kotlin.math.abs(before.y-moved.world.friends.first().y))
    }

    @Test fun islandChaseDoesNotDropGoalWhenOriginalMonsterWalks() {
        val b=island()
        val mob=BlockIslandRules.newWorld(27,listOf("alice")).world.mobs.first()
        val current=b.copy(world=b.world.copy(mobs=listOf(mob.copy(id=99,x=17,y=5))))
        val picked=IslandActorPlay.options(current,"alice")
            .first { it.id=="approach:17:5" }
        val started=IslandActorPlay.apply(current,"alice",current.world.turns,
            IslandActorChoice(picked.id,"我去支援你"))!!
        assertEquals(99,started.companionTravel["alice"]?.targetMobId)
        val changed=started.copy(world=started.world.copy(
            mobs=listOf(mob.copy(id=99,x=17,y=6))))
        val next=nextIsland(changed)
        assertNotNull(next.companionTravel["alice"])
        assertTrue(next.world.events.any { it.contains("路线前往") })
        val cleared=next.copy(world=next.world.copy(mobs=emptyList()))
        assertTrue(nextIsland(cleared).companionTravel.isEmpty())
    }
}
