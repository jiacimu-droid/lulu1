package com.jiacimu.lulu.games

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class IslandHouseProjectsTest {
    private fun clear(): BlockIslandState {
        val original=BlockIslandRules.newWorld(42,listOf("alice"))
        val w=original.world
        return original.copy(supplyCaches=emptyList(),
            world=w.copy(
                tiles=List(w.width*w.height) { 0 },
                wood=100,
                mobs=emptyList(),
            ))
    }

    private fun start(): BlockIslandState {
        val state=clear()
        val option=IslandActorPlay.options(state,"alice").first { it.kind=="start_house" }
        val result=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice(option.id,"我想盖一个家","慢慢建一座小屋"))!!
        assertNotNull(result.houseProject)
        assertEquals(0,result.houseProject!!.placed.size)
        assertEquals(state.world.wood,result.world.wood)
        return result
    }

    private fun nextTurn(s: BlockIslandState): BlockIslandState {
        val advanced=s.copy(world=s.world.copy(turns=s.world.turns+1))
        return IslandHouseProjects.advance(advanced,setOf("alice"))
    }

    @Test fun modelStartsHouseAndProjectBuildsRealTilesWithoutTeleporting() {
        var state=start()
        val project=state.houseProject!!
        val owner=state.world.friends.first()
        var seenProgress=false
        repeat(70) {
            val before=state
            state=nextTurn(state)
            if(state.houseProject?.placed?.size ?: 0 > 0)seenProgress=true
            val now=state.world.friends.first()
            assertTrue(kotlin.math.abs(now.x-before.world.friends.first().x)+
                kotlin.math.abs(now.y-before.world.friends.first().y)<=1)
            assertTrue(state.world.wood<=before.world.wood)
            if(state.houseProject?.phase=="complete")return@repeat
        }
        assertTrue(seenProgress)
        assertEquals("complete",state.houseProject?.phase)
        assertEquals(9,state.houseProject!!.placed.size)
        assertEquals(83,state.world.wood)
        assertEquals(1,state.structures.count { it.value=="door" })
        assertEquals(owner.id,state.houseProject!!.ownerId)
    }

    @Test fun speakingCanPauseAndResumeEvenWhenActorAlreadyWorkedThisTurn() {
        var s=start()
        s=nextTurn(s)
        assertTrue(s.world.events.any {
            it.startsWith(IslandActorPlay.eventPrefix("alice",s.world.turns)) })
        val before=s.world.tiles
        val pause=IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice("pause_house","先别盖了"))!!
        assertEquals("paused",pause.houseProject?.phase)
        val held=nextTurn(pause)
        assertEquals(before,held.world.tiles)
        val resume=IslandActorPlay.apply(held,"alice",held.world.turns,
            IslandActorChoice("resume_house","我再继续试试"))!!
        assertEquals("building",resume.houseProject?.phase)
        assertEquals("paused",IslandHouseProject.read(
            JSONObject(pause.houseProject!!.json().toString()),pause.world)?.phase)
    }

    @Test fun demolitionReclaimsMaterialsOnePieceAtATimeAndPersists() {
        var s=start()
        repeat(70) { if(s.houseProject?.phase!="complete")s=nextTurn(s) }
        assertEquals("complete",s.houseProject?.phase)
        val built=s
        val changed=IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice("demolish_house","那就一点点拆掉吧"))!!
        assertEquals("demolishing",changed.houseProject?.phase)
        assertEquals(built.world.tiles,changed.world.tiles)
        s=changed
        repeat(70) { if(s.houseProject!=null)s=nextTurn(s) }
        assertNull(s.houseProject)
        assertEquals(0,s.structures.size)
        val indices=IslandHouseProjects.parts(s.world,built.houseProject!!.left,built.houseProject!!.top)
            .map { it.first }
        assertTrue(indices.all { s.world.tiles[it]==0 })
        assertEquals(92,s.world.wood)
        val loaded=BlockIslandState.fromJson(s.toJson())!!
        assertNull(loaded.houseProject)
        assertEquals(s.world.wood,loaded.world.wood)
    }

    @Test fun demolitionStopsSafelyWhenSomeoneChangesTheBuilding() {
        var s=start()
        repeat(70) { if(s.houseProject?.phase!="complete")s=nextTurn(s) }
        assertEquals("complete",s.houseProject?.phase)
        val door=IslandHouseProjects.parts(s.world,s.houseProject!!.left,s.houseProject!!.top)
            .last().first
        val edited=s.copy(world=s.world.copy(tiles=s.world.tiles.toMutableList().also { it[door]=0 }),
            structures=s.structures-door)
        val demolish=IslandActorPlay.apply(edited,"alice",edited.world.turns,
            IslandActorChoice("demolish_house","拆房"))!!
        val result=nextTurn(demolish)
        assertEquals("paused",result.houseProject?.phase)
        assertEquals(0,result.world.tiles[door])
        assertEquals(demolish.world.wood,result.world.wood)
    }

    @Test fun oldSaveWithoutPlanRestoresAndInvalidPlanCannotCorruptSave() {
        val initial=clear()
        val json=JSONObject(initial.toJson())
        json.remove("houseProject")
        assertNull(BlockIslandState.fromJson(json.toString())!!.houseProject)
        json.put("houseProject",JSONObject().put("ownerId","alice")
            .put("left",-5).put("top",2).put("phase","building")
            .put("placed",org.json.JSONArray()).put("lastStepTurn",0))
        assertNull(BlockIslandState.fromJson(json.toString())!!.houseProject)
    }

    @Test fun actualPlayerTurnProgressesProjectOnlyOnce() {
        val started=start()
        val w=started.world
        val after=BlockIslandRules.act(started,"move",w.x+1,w.y,setOf("alice"))
        assertEquals(w.turns+1,after.world.turns)
        assertEquals(after.world.turns,after.houseProject?.lastStepTurn)
        val duplicated=IslandHouseProjects.advance(after,setOf("alice"))
        assertEquals(after,duplicated)
    }
}
