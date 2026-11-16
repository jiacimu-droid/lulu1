package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The companion owns the goal. A local clock may advance the goal, not invent one. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class GameCoopPacingTest {
    private val roster = setOf("alice", "bob")

    @Test fun aPlannedTileNeverBillsUnrelatedIdleTeammates() {
        val finished = setOf("alice")
        assertTrue(GameCoopPacing.mayReplanAfterTick(7,7,"alice",finished,false))
        assertFalse("bob has no new owner input and no completed plan",
            GameCoopPacing.mayReplanAfterTick(7,7,"bob",finished,false))
        assertTrue("player can redirect bob while alice is still walking",
            GameCoopPacing.mayReplanAfterTick(7,7,"bob",finished,true))
        assertTrue("an ordinary real player turn may resume normal decision",
            GameCoopPacing.mayReplanAfterTick(8,7,"bob",finished,false))
        assertFalse("a continuing planned route needs no extra Game-model call",
            GameCoopPacing.mayReplanAfterTick(7,7,"alice",emptySet(),false))
    }

    @Test fun idleClockRequiresAnActualChosenPlanAndAnActiveForeground() {
        val yes = GameCoopPacing.canTick(
            true, true, false, false, false, false)
        assertTrue(yes)
        assertFalse(GameCoopPacing.canTick(false,true,false,false,false,false))
        assertFalse(GameCoopPacing.canTick(true,false,false,false,false,false))
        assertFalse(GameCoopPacing.canTick(true,true,true,false,false,false))
        assertFalse(GameCoopPacing.canTick(true,true,false,true,false,false))
        assertFalse(GameCoopPacing.canTick(true,true,false,false,true,false))
        assertFalse(GameCoopPacing.canTick(true,true,false,false,false,true))
    }

    @Test fun islandPlannedStepsAdvanceWithoutMovingPlayerOrBuyingAnAction() {
        val base = BlockIslandRules.newWorld(177,roster.toList())
        val tiles = MutableList(base.world.tiles.size) { 0 }
        tiles[base.world.index(13,5)] = 1
        val s = base.copy(world = base.world.copy(
            x=2,y=2,tiles=tiles,mobs=emptyList(),
            friends=listOf(ExpeditionFriend("alice",5,5),ExpeditionFriend("bob",5,7)),
            turns=0,events=emptyList()),structures=emptyMap())
        assertEquals(s,BlockIslandRules.act(s,"companion_tick",
            autonomousIds=roster))
        val selected = IslandActorPlay.apply(s,"alice",0,
            IslandActorChoice("approach:13:5","我先去看看那片树林"))!!
        assertTrue(GameCoopPacing.islandMemberHasPlan(selected,"alice"))
        assertFalse(GameCoopPacing.islandMemberHasPlan(selected,"bob"))
        val next = BlockIslandRules.act(selected,"companion_tick",
            autonomousIds=roster)
        assertEquals(1,next.world.turns)
        assertEquals(2 to 2,next.world.x to next.world.y)
        assertEquals(1,kotlin.math.abs(
            next.world.friends.first().x - selected.world.friends.first().x) +
            kotlin.math.abs(
            next.world.friends.first().y - selected.world.friends.first().y))
        assertEquals(selected.world.wood,next.world.wood)
        assertTrue(next.world.events.any { it.contains("沿自己选定的路线") })
        val stopped=IslandActorPlay.apply(next,"alice",next.world.turns,
            IslandActorChoice("stop_travel","你说得对，先别过去"))!!
        assertFalse(GameCoopPacing.islandMemberHasPlan(stopped,"alice"))
        assertEquals(stopped,BlockIslandRules.act(stopped,"companion_tick",
            autonomousIds=roster))
    }

    @Test fun downedPlayerDoesNotFreezeAnExplicitlyChosenRescueRoute() {
        val base=RelicEscapeWorld.create(77,1,roster.toList(),1,1)
        val down=base.copy(
            phase=RelicPhase.DOWNED,
            hero=base.hero.copy(hp=0),
            mobs=emptyList(),
            coopPlans=mapOf("alice" to RelicCoopPlan("rescue",lastStepTurn=base.turns)),
        )
        assertTrue(GameCoopPacing.relicHasPlan(down))
        assertFalse("the rescue plan must still move after a down",GameCoopPacing.relicThreat(down))
        assertTrue(GameCoopPacing.relicThreat(down.copy(coopPlans=emptyMap())))
        val incapacitated=down.copy(allies=down.allies.map {
            if(it.id=="alice")it.copy(kits=0)else it
        })
        assertTrue(GameCoopPacing.relicThreat(incapacitated))
    }

    @Test fun idleRelicPartnerCannotFinishExtractionOrUseUpRescueTime() {
        val base = RelicEscapeWorld.create(77,1,roster.toList(),1,1)
        val board = base.copy(
            tiles = List(base.w * base.h) { 0 }, sites = emptyList(), chests = emptyList(),
            allies = listOf(base.allies.first().copy(x = 8, y = 8, kits = 1)),
        )
        val extracting = board.copy(
            phase = RelicPhase.EXTRACT, extraction = 2, flareTurns = 3,
            coopPlans = mapOf("alice" to RelicCoopPlan("regroup", lastStepTurn = board.turns)),
        )
        val after = RelicEscapeRules.act(extracting, "companion_tick", autonomousIds = roster)
        assertEquals(extracting.turns + 1, after.turns)
        assertNotEquals(extracting.allies, after.allies)
        assertEquals(RelicPhase.EXTRACT, after.phase)
        assertEquals(extracting.extraction, after.extraction)
        assertEquals(extracting.flareTurns, after.flareTurns)
        assertEquals(extracting.hero, after.hero)
        assertEquals(extracting.mobs, after.mobs)

        val down = board.copy(
            phase = RelicPhase.DOWNED, hero = board.hero.copy(hp = 0), downTurns = 3,
            coopPlans = mapOf("alice" to RelicCoopPlan("rescue", lastStepTurn = board.turns)),
        )
        val approaching = RelicEscapeRules.act(down, "companion_tick", autonomousIds = roster)
        assertNotEquals(down.allies, approaching.allies)
        assertEquals(RelicPhase.DOWNED, approaching.phase)
        assertEquals(down.downTurns, approaching.downTurns)
        assertEquals(RelicPhase.FAILURE, RelicEscapeRules.act(
            down.copy(coopPlans = emptyMap(), mobs = emptyList()), "wait", autonomousIds = roster,
        ).phase)
    }

    @Test fun relicPlannedStepsUseRealTurnAndStopAfterOwnerChangesMind() {
        val base=RelicEscapeWorld.create(77,1,roster.toList(),1,1)
        val s=base.copy(tiles=List(base.w*base.h){0},mobs=emptyList(),
            sites=emptyList(),chests=emptyList(),seen=base.tiles.indices.toSet(),
            allies=base.allies.map { if(it.id=="alice")it.copy(x=8,y=8)else it })
        assertEquals(s,RelicEscapeRules.act(s,"companion_tick",
            autonomousIds=roster))
        val plan=RelicActorPlay.apply(s,"alice",
            IslandActorChoice("plan_regroup","我过去和你一起行动"))!!
        assertTrue(GameCoopPacing.relicHasPlan(plan))
        val next=RelicEscapeRules.act(plan,"companion_tick",autonomousIds=roster)
        assertEquals(plan.turns+1,next.turns)
        assertEquals(s.hero.x to s.hero.y,next.hero.x to next.hero.y)
        assertNotEquals(plan.allies.first().x to plan.allies.first().y,
            next.allies.first().x to next.allies.first().y)
        assertFalse(next.events.any {
            it.who=="player" && it.action=="wait" && it.turn==next.turns
        })
        val cancelled=RelicActorPlay.apply(next,"alice",
            IslandActorChoice("stop_travel","换个方向吧"))!!
        assertFalse(GameCoopPacing.relicHasPlan(cancelled))
        assertEquals(cancelled,RelicEscapeRules.act(cancelled,"companion_tick",
            autonomousIds=roster))
    }
}
