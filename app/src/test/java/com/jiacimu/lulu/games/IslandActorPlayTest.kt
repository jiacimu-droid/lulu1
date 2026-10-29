package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Android JSONObject and the persisted island codec must run against a real Robolectric runtime. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandActorPlayTest {
    private fun world(): BlockIslandState {
        val original=BlockIslandRules.newWorld(1337,listOf("alice","bob"))
        return original.copy(world=original.world.copy(mobs=emptyList()))
    }

    @Test fun companionChoosesOwnLegalMoveAndDoesNotMovePlayer() {
        val s=world()
        val option=IslandActorPlay.options(s,"alice").first{it.kind=="move"}
        val result=IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice(option.id,"我去那边看看"))!!
        assertEquals(option.x,result.world.friends.first().x)
        assertEquals(option.y,result.world.friends.first().y)
        assertEquals(s.world.x,result.world.x)
        assertEquals(s.world.y,result.world.y)
        assertEquals(s.world.turns,result.world.turns)
        assertTrue(result.world.events.last().contains("我去那边看看"))
        assertNotNull(BlockIslandState.fromJson(result.toJson()))
    }

    @Test fun noDuplicateStaleOrInventedAction() {
        val s=world()
        val option=IslandActorPlay.options(s,"alice").first{it.kind=="move"}
        val choice=IslandActorChoice(option.id,"")
        assertNull(IslandActorPlay.apply(s,"alice",s.world.turns+1,choice))
        assertNull(IslandActorPlay.apply(s,"fake",s.world.turns,choice))
        assertNull(IslandActorPlay.apply(s,"alice",s.world.turns,
            IslandActorChoice("give-me-100-wood","")))
        val result=IslandActorPlay.apply(s,"alice",s.world.turns,choice)!!
        assertNull(IslandActorPlay.apply(result,"alice",result.world.turns,choice))
    }

    @Test fun gatheringChangesActualMapAndResourcesOnce() {
        val s=world()
        val a=s.world.friends.first()
        val x=a.x+1;val y=a.y
        val w=s.world.copy(tiles=s.world.tiles.toMutableList().also{it[s.world.index(x,y)]=1})
        val prepared=s.copy(world=w)
        val choice=IslandActorChoice("gather:$x:$y","")
        val result=IslandActorPlay.apply(prepared,"alice",w.turns,choice)!!
        assertEquals(w.wood+1,result.world.wood)
        assertEquals(0,result.world.cell(x,y))
        assertNull(IslandActorPlay.apply(result,"alice",w.turns,choice))
    }

    @Test fun independentExplorationApproachesRealTargetWithoutTeleportingOrReplacingPlayerAction() {
        val s = world()
        val actor = s.world.friends.first()
        val destination = 5 to 9
        // Isolate the objective from unrelated procedural trees so the candidate
        // ranking, rather than map seed noise, is what this regression checks.
        val tiles = MutableList(s.world.tiles.size) { 0 }
        tiles[s.world.index(destination.first, destination.second)] = 2
        val prepared = s.copy(world = s.world.copy(tiles = tiles))
        val option = IslandActorPlay.options(prepared, actor.id)
            .first { it.id == "approach:${destination.first}:${destination.second}" }
        val decision = IslandActorChoice(option.id, "这边有矿，我先去看看", "自己去矿旁")
        val result = IslandActorPlay.apply(prepared, actor.id, prepared.world.turns, decision)!!
        val moved = result.world.friends.first()
        assertEquals(1, kotlin.math.abs(actor.x - moved.x) + kotlin.math.abs(actor.y - moved.y))
        assertEquals(prepared.world.x to prepared.world.y, result.world.x to result.world.y)
        assertEquals(prepared.world.stone, result.world.stone)
        assertEquals(2, result.world.cell(destination.first, destination.second))
        assertTrue(result.world.events.last().contains("下一步想：自己去矿旁"))
        assertNull(IslandActorPlay.apply(result, actor.id, prepared.world.turns, decision))
    }

    @Test fun blockedOrInventedDistantObjectivesCannotMoveOrCreateResources() {
        val s = world()
        val actor = s.world.friends.first()
        assertNull(IslandActorPlay.apply(s, actor.id, s.world.turns,
            IslandActorChoice("approach:999:999", "", "去地图外")))
        val near = s.world.tiles.toMutableList()
        val dest = 10 to 4
        near[s.world.index(dest.first, dest.second)] = 1
        val world = s.copy(world = s.world.copy(tiles = near))
        val choice = IslandActorPlay.options(world, actor.id)
            .firstOrNull { it.id == "approach:10:4" }
        if (choice != null) {
            val original = world.world.friends.first()
            val applied = IslandActorPlay.apply(world, actor.id, world.world.turns,
                IslandActorChoice(choice.id, ""))!!
            assertEquals(world.world.wood, applied.world.wood)
            assertEquals(world.world.stone, applied.world.stone)
            assertEquals(1, kotlin.math.abs(original.x - applied.world.friends.first().x) +
                kotlin.math.abs(original.y - applied.world.friends.first().y))
        }
    }

    @Test fun teammateIndependentMovementAvoidsPlayerAndOtherTeammateTiles() {
        val base = world()
        val actor = base.world.friends.first()
        val other = base.world.friends[1]
        val obstacle = (actor.x + 1) to actor.y
        val situated = base.copy(world = base.world.copy(
            x = obstacle.first, y = obstacle.second,
            friends = listOf(actor, other.copy(x = actor.x, y = actor.y + 1)),
        ))
        val actions = IslandActorPlay.options(situated, actor.id)
        assertFalse(actions.any { it.kind == "move" &&
            (it.x to it.y == obstacle || it.x to it.y == (actor.x to actor.y + 1)) })
        assertNull(IslandActorPlay.apply(situated, actor.id, situated.world.turns,
            IslandActorChoice("move:${obstacle.first}:${obstacle.second}", "")))
    }

    @Test fun companionCanChooseToAidWoundedPlayerWithRealFruitCost() {
        val s = world()
        val actor = s.world.friends.first()
        val prepared = s.copy(fruit = 1, world = s.world.copy(
            hp = 5,
            x = actor.x, y = actor.y + 1,
        ))
        assertTrue(IslandActorPlay.options(prepared, actor.id).any { it.id == "help_player" })
        val result = IslandActorPlay.apply(prepared, actor.id, prepared.world.turns,
            IslandActorChoice("help_player", "你先吃点东西，我去看看周围"))!!
        assertEquals(8, result.world.hp)
        assertEquals(0, result.fruit)
        assertEquals(prepared.world.turns, result.world.turns)
        assertTrue(result.world.events.last().contains("帮助玩家"))
        assertNull(IslandActorPlay.apply(result, actor.id, result.world.turns,
            IslandActorChoice("help_player", "")))
    }

    @Test fun rescueNeedsActualDownedTeammateAndTwoFruit() {
        val base = world()
        val alice = base.world.friends.first()
        val bob = base.world.friends[1]
        val prepared = base.copy(fruit = 2, world = base.world.copy(
            friends = listOf(alice, bob.copy(hp = 0)),
        ))
        val option = IslandActorPlay.options(prepared, "alice").first { it.kind == "revive" }
        val result = IslandActorPlay.apply(prepared, "alice", prepared.world.turns,
            IslandActorChoice(option.id, "我来救你"))!!
        assertEquals(4, result.world.friends[1].hp)
        assertEquals(0, result.fruit)
        val noFood = prepared.copy(fruit = 1)
        assertFalse(IslandActorPlay.options(noFood, "alice").any { it.kind == "revive" })
    }

    @Test fun invalidStructuredChoicesAreRejectedNotReinterpreted() {
        assertNull(IslandActorPlay.parse("我去采集"))
        assertNull(IslandActorPlay.parse("""{"actionId":"none"}"""))
        assertEquals(IslandActorChoice("none",""),
            IslandActorPlay.parse("""{"actionId":"none","speech":""}"""))
        val fence = 96.toChar().toString().repeat(3)
        assertEquals(IslandActorChoice("none",""),
            IslandActorPlay.parse(fence+"json\n"+"""{"actionId":"none","speech":""}"""+"\n"+fence))
    }
}
