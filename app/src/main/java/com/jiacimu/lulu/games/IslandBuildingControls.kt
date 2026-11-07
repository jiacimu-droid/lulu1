package com.jiacimu.lulu.games

/** In-world construction is a mode: choosing a recipe never consumes supplies or a turn.
 * Only a confirmed placement goes through BlockIslandRules.act and the original save path.
 */
internal data class IslandBuildRecipe(val action: String, val name: String, val cost: String)

internal data class IslandBuildPreview(
    val action: String, val x: Int, val y: Int, val allowed: Boolean,
    val message: String = "",
)

internal object IslandBuildingControls {
    val recipes = listOf(
        IslandBuildRecipe("workbench", "⚒ 工作台", "4木2石"),
        IslandBuildRecipe("campfire", "♨ 篝火", "2木1石"),
        IslandBuildRecipe("bed", "▱ 木床", "3木1石"),
        IslandBuildRecipe("lantern", "✦ 夜灯", "2木1石"),
        IslandBuildRecipe("pet_house", "⌂ 宠物小屋", "4木2石"),
        IslandBuildRecipe("bridge", "▦ 木桥", "3木"),
        IslandBuildRecipe("wall", "▥ 木墙", "2木"),
        IslandBuildRecipe("floor", "▤ 地板", "1木"),
        IslandBuildRecipe("door", "▣ 木门", "2木"),
        IslandBuildRecipe("plant", "♣ 种树", "1木"),
        IslandBuildRecipe("remove", "↶ 拆除", "返还材料"),
    )

    /** Show why a position is blocked, but let Rules remain the only authority.
     * No preview can spend a turn, modify inventory, or place a structure.
     */
    fun preview(s: BlockIslandState, action: String?, x: Int, y: Int): IslandBuildPreview? {
        if (action == null || recipes.none { it.action == action } || !s.world.inBounds(x, y))
            return null
        val w = s.world
        val locationOK = when (action) {
            "plant" -> BlockIslandRules.canPlantAt(s, x, y)
            "remove" -> BlockIslandRules.canRemoveAt(s, x, y)
            else -> BlockIslandRules.canPlaceBuilding(s, action, x, y)
        }
        val allowed = locationOK && when(action) {
            "plant" -> w.wood >= 1
            "remove" -> true
            else -> BlockIslandRules.canBuild(s, action, x, y)
        }
        val message = when {
            w.finished -> "当前岛屿无法继续建造"
            kotlin.math.abs(w.x-x) + kotlin.math.abs(w.y-y) > 1 ->
                "需要站到目标旁边（1格内）"
            !locationOK -> "地形不符或位置被占用"
            !allowed -> "材料不足，继续采集木材和石料"
            else -> "可放置 · 点击建造"
        }
        return IslandBuildPreview(action, x, y, allowed, message)
    }

    /** Hotbar browsing only changes selected UI recipe, not the island or its turn. */
    fun cycle(current: String?, direction: Int): String {
        val index = recipes.indexOfFirst { it.action == current }.let { if (it < 0) 0 else it }
        val next = (index + (if(direction < 0) -1 else 1) + recipes.size) % recipes.size
        return recipes[next].action
    }

    fun label(action: String?): String =
        recipes.firstOrNull { it.action == action }?.name ?: "建造"
}
