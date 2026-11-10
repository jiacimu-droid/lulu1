package com.jiacimu.lulu.games

import kotlin.math.abs

/**
 * Physical destinations on the connected east/south starter trails.
 * Both coordinates are guaranteed clear by BlockIslandRules.newWorld.
 * They are optional discoveries: an accepted model companion can lead the party,
 * but no UI recomposition, dialogue or phantom action can grant their reward.
 *
 * Awarded IDs live in BlockIslandState.awardedChallenges, not a second save file.
 */
internal data class IslandLandmark(
    val id:String,
    val title:String,
    val description:String,
    val x:Int,
    val y:Int,
    val wood:Int,
    val stone:Int,
    val fruit:Int,
    val fish:Int=0,
) {
    val rewardText:String
        get()=listOfNotNull(
            wood.takeIf { it>0 }?.let { "木材 +$it" },
            stone.takeIf { it>0 }?.let { "石料 +$it" },
            fruit.takeIf { it>0 }?.let { "野果 +$it" },
            fish.takeIf { it>0 }?.let { "小鱼 +$it" },
        ).joinToString("、")
}

internal object IslandExpeditionTrail {
    val landmarks=listOf(
        IslandLandmark(
            id="cape_beacon",title="晨光海角",description="沿东部海岸发现第一座航标",
            x=21,y=5,wood=3,stone=2,fruit=2,
        ),
        IslandLandmark(
            id="southern_shrine",title="南岭古树",description="穿过南线石径，找到古树祭坛",
            x=21,y=BlockIslandRules.WORLD_HEIGHT-4,wood=4,stone=3,fruit=3,fish=2,
        ),
    )

    fun distance(s:BlockIslandState,place:IslandLandmark):Int =
        abs(s.world.x-place.x)+abs(s.world.y-place.y)

    // The goal is a small physical approach zone; a monster standing on the
    // marker may not force the player to enter an occupied enemy tile.
    fun reached(s:BlockIslandState,place:IslandLandmark):Boolean =
        distance(s,place)<=1

    fun next(s:BlockIslandState):IslandLandmark? =
        landmarks.firstOrNull { it.id !in s.awardedChallenges }

    fun completedCount(s:BlockIslandState):Int =
        landmarks.count { it.id in s.awardedChallenges }
}
