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
        near && w.cell(x, y) in setOf(1, 2) -> IslandTargetAction("gather", "采集", true, distance)
        near && w.cell(x, y) == 8 -> IslandTargetAction(
            "fish", if ("rod" in state.tools) "钓鱼" else "需要鱼竿",
            "rod" in state.tools, distance)
        distance == 0 -> IslandTargetAction("none", "当前位置", false, distance)
        else -> IslandTargetAction("move", "前往", true, distance)
    }
}
