package com.jiacimu.lulu.games

import kotlin.math.abs

/** One source of truth for the primary island action across touch and HUD. */
internal data class IslandTargetAction(
    val action: String,
    val label: String,
    val enabled: Boolean,
    val distance: Int,
)

internal fun islandTargetAction(state: BlockIslandState, target: Pair<Int, Int>): IslandTargetAction {
    val w = state.world
    val (x, y) = target
    val distance = abs(w.x - x) + abs(w.y - y)
    if (w.finished || !w.inBounds(x, y)) return IslandTargetAction("none", "无法操作", false, distance)
    val near = distance <= 1
    val downed = w.friends.any { it.hp <= 0 && it.x == x && it.y == y }
    val mob = w.mobs.any { it.hp > 0 && it.x == x && it.y == y }
    return when {
        near && downed -> IslandTargetAction(
            "revive", if (state.fruit >= 2) "救援队友" else "救援需2果", state.fruit >= 2, distance)
        near && mob -> IslandTargetAction("fight", "攻击", true, distance)
        near && IslandSupplyCaches.available(state, x, y) ->
            IslandTargetAction("salvage", "打开补给", true, distance)
        near && state.animals.any { it.x == x && it.y == y } -> {
            val resident=state.animals.first { it.x == x && it.y == y }
            if (!resident.befriended) IslandTargetAction("tame",
                if (IslandWildlife.canFeed(state,resident)) "结识"+IslandWildlife.name(resident.species)
                else "需要"+IslandWildlife.feed(resident.species),
                IslandWildlife.canFeed(state,resident),distance)
            else IslandTargetAction("care",
                if(IslandWildlife.canGift(state,resident)) "收取伙伴礼物" else "轻抚"+IslandWildlife.name(resident.species),
                IslandWildlife.canGift(state,resident),distance)
        }
        near && state.structures[w.index(x, y)] == "garden" -> {
            val index = w.index(x, y)
            IslandTargetAction(IslandGarden.action(state,index),
                IslandGarden.actionLabel(state,index),
                IslandGarden.canInteract(state,index),distance)
        }
        near && w.cell(x, y) in setOf(1, 2) -> IslandTargetAction("gather", "采集", true, distance)
        near && w.cell(x, y) == 8 -> IslandTargetAction(
            "fish", if ("rod" in state.tools) "钓鱼" else "需要鱼竿",
            "rod" in state.tools, distance)
        distance == 0 -> IslandTargetAction("none", "当前位置", false, distance)
        else -> IslandTargetAction("move", "前往", true, distance)
    }
}

/** Lightweight HUD target; no map tap or extra game turn is required to choose it.
 * Direction (last D-pad input) wins, with the other three neighboring cells as fallback.
 * Only real, currently enabled rules actions can be returned.
 */
internal data class IslandNearbyAction(val action: String, val label: String, val x: Int, val y: Int)

internal fun islandNearbyAction(
    state: BlockIslandState,
    facing: Pair<Int, Int>,
    allowed: Set<String> = setOf("fight", "revive", "salvage", "gather", "fish", "tame", "care", "sow_berries", "tend_berries", "harvest_berries"),
): IslandNearbyAction? {
    val w = state.world
    if (w.finished) return null
    val directions = listOf(facing, 0 to -1, 1 to 0, 0 to 1, -1 to 0)
        .filter { (dx,dy) -> kotlin.math.abs(dx)+kotlin.math.abs(dy)==1 }
        .distinct()
    for ((dx,dy) in directions) {
        val x=w.x+dx
        val y=w.y+dy
        if (!w.inBounds(x,y)) continue
        val candidate=islandTargetAction(state,x to y)
        if (candidate.enabled && candidate.action in allowed)
            return IslandNearbyAction(candidate.action,candidate.label,x,y)
    }
    return null
}


/** Campsite is a physical interaction, not a recipe or inventory menu option. */
internal fun islandCampNearby(state: BlockIslandState): Boolean {
    val w = state.world
    return state.structures.any { (location, kind) ->
        kind in setOf("campfire", "bed") &&
            abs(w.x - location % w.width) + abs(w.y - location / w.width) <= 1
    }
}

internal fun islandCampRestAvailable(state: BlockIslandState): Boolean {
    val w = state.world
    return !w.finished && w.hp in 1..11 &&
        w.turns - state.lastRestTurn >= 8 && islandCampNearby(state)
}
