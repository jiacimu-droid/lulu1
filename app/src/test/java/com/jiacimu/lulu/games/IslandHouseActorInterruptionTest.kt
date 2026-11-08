package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** G-02.2: a character's committed building plan must not create progress while incapacitated. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class IslandHouseActorInterruptionTest {
    @Test fun incapacitatedBuilderCannotSpendWoodOrBuildAndSavedPlanCanContinueAfterRecovery() {
        val original=BlockIslandRules.newWorld(42,listOf("alice"))
        val world=original.world
        val clean=original.copy(supplyCaches=emptyList(),world=world.copy(
            tiles=List(world.width*world.height) { 0 },wood=100,mobs=emptyList()))
        val choice=IslandActorPlay.options(clean,"alice").first { it.kind=="start_house" }
        val planned=IslandActorPlay.apply(clean,"alice",clean.world.turns,
            IslandActorChoice(choice.id,"我来盖房子"))!!
        val disabled=planned.copy(world=planned.world.copy(
            friends=planned.world.friends.map { if(it.id=="alice")it.copy(hp=0) else it },
            turns=planned.world.turns+1))
        val idle=IslandHouseProjects.advance(disabled,setOf("alice"))
        assertEquals(disabled.world.wood,idle.world.wood)
        assertEquals(disabled.world.tiles,idle.world.tiles)
        assertEquals(disabled.houseProject,idle.houseProject)
        val reloaded=BlockIslandState.fromJson(idle.toJson())!!
        assertEquals("building",reloaded.houseProject?.phase)
        val recovered=reloaded.copy(world=reloaded.world.copy(
            friends=reloaded.world.friends.map { if(it.id=="alice")it.copy(hp=10) else it },
            turns=reloaded.world.turns+1))
        val resumed=IslandHouseProjects.advance(recovered,setOf("alice"))
        assertTrue(resumed.world.events.any {
            it.startsWith(IslandActorPlay.eventPrefix("alice",resumed.world.turns))
        })
        assertTrue(resumed.houseProject?.lastStepTurn==resumed.world.turns)
        assertEquals(resumed,IslandHouseProjects.advance(resumed,setOf("alice")))
    }
}
