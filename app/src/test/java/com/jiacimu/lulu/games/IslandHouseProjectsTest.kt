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

    @Test fun hudAndProjectPanelProgressTrackActualDemolitionAcrossPauseAndReload() {
        val world=clear().world
        val built=IslandHouseProject("alice",3,3,phase="complete",
            placed=IslandHouseProjects.parts(world,3,3).map { it.first })
        assertEquals(9,IslandHouseProjects.progress(built))
        val demolishing=built.copy(phase="demolishing")
        assertEquals(0,IslandHouseProjects.progress(demolishing))
        val partiallyRemoved=demolishing.copy(placed=built.placed.dropLast(3))
        assertEquals(3,IslandHouseProjects.progress(partiallyRemoved))
        val paused=partiallyRemoved.copy(phase="paused",resumePhase="demolishing")
        assertEquals(3,IslandHouseProjects.progress(paused))
        val restored=IslandHouseProject.read(paused.json(),world)!!
        assertEquals(3,IslandHouseProjects.progress(restored))
        assertEquals(3,IslandHouseProjects.progress(restored.copy(phase="demolishing")))
        assertEquals(9,IslandHouseProjects.progress(restored.copy(
            phase="demolishing",placed=emptyList())))
        assertEquals(6,IslandHouseProjects.progress(restored.copy(
            phase="paused",resumePhase="building")))
    }

    @Test fun characterChoosesCottageStyleAndSaveMigrationPreservesIt() {
        val world=clear()
        val option=IslandActorPlay.options(world,"alice").first {
            it.id.startsWith("start_house:") && it.id.endsWith(":pine")
        }
        val next=IslandActorPlay.apply(world,"alice",world.world.turns,
            IslandActorChoice(option.id,"我给你盖松林木屋"))!!
        assertEquals("pine",next.houseProject?.style)
        val persisted=BlockIslandState.fromJson(next.toJson())!!
        assertEquals("pine",persisted.houseProject?.style)
        assertEquals("松林木屋",IslandHouseProjects.styleName(persisted.houseProject!!.style))
        val migrated=JSONObject(next.houseProject!!.json().toString())
        migrated.remove("style")
        assertEquals("warm",IslandHouseProject.read(migrated,next.world)?.style)
        migrated.put("style","rogue-asset")
        assertEquals("warm",IslandHouseProject.read(migrated,next.world)?.style)
        assertNull(IslandHouseProjects.choose(world,"alice",
            "start_house:3:3:not-a-real-style",""))
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

    @Test fun pausedDemolitionResumesDemolitionAfterReloadWithoutRebuilding() {
        var state=start()
        repeat(70) { if(state.houseProject?.phase!="complete")state=nextTurn(state) }
        assertEquals("complete",state.houseProject?.phase)
        state=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("demolish_house","我决定拆掉这间旧屋"))!!
        state=nextTurn(state)
        assertEquals("demolishing",state.houseProject?.phase)
        val woodAfterRemoval=state.world.wood
        val removed=state.houseProject!!.placed.size
        state=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("pause_house","先停一下"))!!
        assertEquals("paused",state.houseProject?.phase)
        assertEquals("demolishing",state.houseProject?.resumePhase)
        val restored=BlockIslandState.fromJson(state.toJson())!!
        assertEquals("demolishing",restored.houseProject?.resumePhase)
        val idle=nextTurn(restored)
        assertEquals(woodAfterRemoval,idle.world.wood)
        assertEquals(removed,idle.houseProject?.placed?.size)
        state=IslandActorPlay.apply(idle,"alice",idle.world.turns,
            IslandActorChoice("resume_house","继续拆旧屋"))!!
        assertEquals("demolishing",state.houseProject?.phase)
        repeat(70) { if(state.houseProject!=null)state=nextTurn(state) }
        assertNull(state.houseProject)
        assertEquals(92,state.world.wood)
    }

    @Test fun legacyPausedBuildingDefaultsToBuildingAndInvalidResumeModeIsRejected() {
        val state=start()
        val paused=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("pause_house","先停"))!!
        val legacy=JSONObject(paused.houseProject!!.json().toString())
        legacy.remove("resumePhase")
        assertEquals("building",IslandHouseProject.read(legacy,paused.world)?.resumePhase)
        legacy.put("resumePhase","grant_free_loot")
        assertNull(IslandHouseProject.read(legacy,paused.world))
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
        assertEquals("demolishing",result.houseProject?.resumePhase)
        val reloaded=BlockIslandState.fromJson(result.toJson())!!
        val resumed=IslandHouseProjects.choose(reloaded,"alice","resume_house","继续拆")!!
        assertEquals("demolishing",resumed.houseProject?.phase)
        assertEquals(reloaded.world.wood,resumed.world.wood)
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
    @Test fun exhaustedWoodPausesWithoutPhantomProgressAndResumesOnlyAfterCharacterChoice() {
        var state=start()
        // Walk/build through the same turn advancement used by the real game.
        repeat(35) {
            if(state.houseProject!!.placed.isEmpty()) state=nextTurn(state)
        }
        assertTrue(state.houseProject!!.placed.isNotEmpty())
        val completedBefore=state.houseProject!!.placed.toList()
        val builtTiles=state.world.tiles
        state=state.copy(world=state.world.copy(wood=0))
        repeat(35) {
            if(state.houseProject?.phase=="building")state=nextTurn(state)
        }
        assertEquals("paused",state.houseProject?.phase)
        assertEquals(completedBefore,state.houseProject!!.placed)
        assertEquals(builtTiles,state.world.tiles)
        val saved=BlockIslandState.fromJson(state.toJson())!!
        val stillPaused=nextTurn(saved.copy(world=saved.world.copy(wood=20)))
        assertEquals("paused",stillPaused.houseProject?.phase)
        assertEquals(completedBefore,stillPaused.houseProject!!.placed)
        assertEquals(20,stillPaused.world.wood)
        val chosen=IslandActorPlay.apply(stillPaused,"alice",stillPaused.world.turns,
            IslandActorChoice("resume_house","木头够了，我接着盖"))!!
        assertEquals("building",chosen.houseProject?.phase)
        var resumed=chosen
        repeat(35) {
            if(resumed.houseProject?.placed?.size==completedBefore.size)resumed=nextTurn(resumed)
        }
        assertTrue(resumed.houseProject!!.placed.size>completedBefore.size)
        assertTrue(resumed.world.wood<20)
    }

    @Test fun constructionSaveReloadAndDuplicateTurnNeverChargeTwice() {
        var state=start()
        // The character has already chosen this project; advancing turns is physical execution,
        // not a new model decision or a fresh authorization to spend materials.
        repeat(50) {
            if (state.houseProject?.placed?.isEmpty() == true) state=nextTurn(state)
        }
        val project=state.houseProject!!
        assertTrue(project.placed.isNotEmpty())
        val wood=state.world.wood
        val tiles=state.world.tiles
        val saved=BlockIslandState.fromJson(state.toJson())!!
        assertEquals(project.placed,saved.houseProject?.placed)
        assertEquals(wood,saved.world.wood)
        assertEquals(tiles,saved.world.tiles)

        // Replaying the same committed turn after a save must not mint another wall
        // or spend the same piece of wood again.
        val replay=IslandHouseProjects.advance(saved,setOf("alice"))
        assertEquals(saved,replay)
        assertEquals(wood,replay.world.wood)

        // A genuinely later turn may resume the same character-chosen plan.
        val later=nextTurn(replay)
        assertTrue(later.world.wood<=wood)
        assertTrue(later.houseProject!!.placed.size>=project.placed.size)
        val reloaded=BlockIslandState.fromJson(later.toJson())!!
        assertEquals(later.world.wood,reloaded.world.wood)
        assertEquals(later.houseProject!!.placed,reloaded.houseProject!!.placed)
    }

    @Test fun demolitionSaveReloadAndDuplicateTurnNeverRefundTwice() {
        var state=start()
        repeat(70) { if(state.houseProject?.phase!="complete")state=nextTurn(state) }
        assertEquals("complete",state.houseProject?.phase)
        state=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("demolish_house","拆掉旧屋，材料留着重新盖"))!!
        val before=state.houseProject!!.placed.size
        repeat(50) {
            if(state.houseProject?.placed?.size==before)state=nextTurn(state)
        }
        assertTrue(state.houseProject!!.placed.size<before)
        val saved=BlockIslandState.fromJson(state.toJson())!!
        val wood=saved.world.wood
        val placed=saved.houseProject!!.placed
        val tiles=saved.world.tiles
        val replay=IslandHouseProjects.advance(saved,setOf("alice"))
        assertEquals(saved,replay)
        assertEquals(wood,replay.world.wood)
        assertEquals(placed,replay.houseProject?.placed)
        assertEquals(tiles,replay.world.tiles)
        val later=nextTurn(replay)
        assertTrue(later.world.wood>=wood)
        assertTrue((later.houseProject?.placed?.size ?: 0)<=placed.size)
        val restored=BlockIslandState.fromJson(later.toJson())!!
        assertEquals(later.world.wood,restored.world.wood)
        assertEquals(later.houseProject?.placed,restored.houseProject?.placed)
    }


    @Test fun demolitionPausesForAnimalOnDoorWithoutRefundOrDestroyingItsTile() {
        var state=start()
        repeat(70) { if(state.houseProject?.phase!="complete")state=nextTurn(state) }
        assertEquals("complete",state.houseProject?.phase)
        val p=state.houseProject!!
        val door=IslandHouseProjects.parts(state.world,p.left,p.top).last().first
        val x=door%state.world.width
        val y=door/state.world.width
        // An animal may legitimately stand on a doorway tile (value 4).
        state=state.copy(animals=listOf(IslandAnimal(901,"hare",x,y)))
        state=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("demolish_house","我来拆旧屋"))!!
        val beforeWood=state.world.wood
        val beforeTiles=state.world.tiles
        val beforePlaced=state.houseProject!!.placed
        val blocked=nextTurn(state)
        assertEquals("paused",blocked.houseProject?.phase)
        assertEquals("demolishing",blocked.houseProject?.resumePhase)
        assertEquals(beforeWood,blocked.world.wood)
        assertEquals(beforeTiles,blocked.world.tiles)
        assertEquals(beforePlaced,blocked.houseProject?.placed)
        val restored=BlockIslandState.fromJson(blocked.toJson())!!
        assertEquals(beforeWood,restored.world.wood)
        assertEquals(4,restored.world.tiles[door])
        assertEquals("paused",restored.houseProject?.phase)
        assertEquals("hare",restored.animals.single().species)
    }

    @Test fun animalLeavesPausedDemolitionOnlyCharacterResumeCanRefundOnce() {
        var state=start()
        repeat(70) { if(state.houseProject?.phase!="complete")state=nextTurn(state) }
        assertEquals("complete",state.houseProject?.phase)
        val p=state.houseProject!!
        val door=IslandHouseProjects.parts(state.world,p.left,p.top).last().first
        val x=door%state.world.width
        val y=door/state.world.width
        state=state.copy(animals=listOf(IslandAnimal(901,"hare",x,y)))
        state=IslandActorPlay.apply(state,"alice",state.world.turns,
            IslandActorChoice("demolish_house","拆掉旧屋"))!!
        val blocked=nextTurn(state)
        assertEquals("paused",blocked.houseProject?.phase)
        val saved=BlockIslandState.fromJson(blocked.toJson())!!
        // Moving the animal does not silently turn a safety pause into permission.
        val cleared=saved.copy(animals=emptyList())
        val idle=nextTurn(cleared)
        assertEquals("paused",idle.houseProject?.phase)
        assertEquals(saved.world.wood,idle.world.wood)
        assertEquals(4,idle.world.tiles[door])
        val resumed=IslandActorPlay.apply(idle,"alice",idle.world.turns,
            IslandActorChoice("resume_house","小动物走开了，我继续拆"))!!
        assertEquals("demolishing",resumed.houseProject?.phase)
        var progress=resumed
        repeat(30) {
            if(progress.houseProject?.placed?.size==p.placed.size)progress=nextTurn(progress)
        }
        assertEquals(p.placed.size-1,progress.houseProject?.placed?.size)
        assertEquals(saved.world.wood+1,progress.world.wood)
        assertEquals(0,progress.world.tiles[door])
        val persisted=BlockIslandState.fromJson(progress.toJson())!!
        assertEquals(persisted,IslandHouseProjects.advance(persisted,setOf("alice")))
    }
}
