package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test

class RelicIdleCompanionLootTest {
    @Test fun idleCompanionCannotChooseChestOrMintLootWithoutCharacterPlan() {
        val generated=RelicEscapeWorld.create(7291,2,listOf("ally"),0,1)
        val chest=generated.chests.first()
        val beside=listOf(1 to 0,-1 to 0,0 to 1,0 to -1)
            .map { (dx,dy) -> chest.x+dx to chest.y+dy }
            .first { (x,y) -> generated.walk(x,y) }
        val ally=generated.allies.first().copy(x=beside.first,y=beside.second)
        val world=generated.copy(
            allies=listOf(ally),
            mobs=emptyList(),
            sites=emptyList(),
        )
        val after=RelicEscapeRules.act(world,"wait",autonomousIds=emptySet())
        assertEquals(world.chests,after.chests)
        assertEquals(world.allies.first().bag,after.allies.first().bag)
        assertEquals(world.allies.first().x,after.allies.first().x)
        assertEquals(world.allies.first().y,after.allies.first().y)
        assertTrue(after.events.none { it.who==ally.id && it.action=="open" })
    }
}
