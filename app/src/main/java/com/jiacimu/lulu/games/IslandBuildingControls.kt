package com.jiacimu.lulu.games

/** In-world construction is a mode: choosing a recipe never consumes supplies or a turn.
 * Only a confirmed placement goes through BlockIslandRules.act and the original save path.
 */
internal data class IslandBuildRecipe(val action: String, val name: String, val cost: String)

internal data class IslandBuildPreview(
    val action: String, val x: Int, val y: Int, val allowed: Boolean,
)

internal object IslandBuildingControls {
    val recipes = listOf(
        IslandBuildRecipe("workbench", "⚒ 工作台", "4木2石"),
        IslandBuildRecipe("campfire", "♨ 篝火", "2木1石"),
        IslandBuildRecipe("bed", "▱ 木床", "3木1石"),
        IslandBuildRecipe("bridge", "▦ 木桥", "3木"),
        IslandBuildRecipe("wall", "▥ 木墙", "2木"),
        IslandBuildRecipe("floor", "▤ 地板", "1木"),
        IslandBuildRecipe("door", "▣ 木门", "2木"),
        IslandBuildRecipe("plant", "♣ 种树", "1木"),
        IslandBuildRecipe("remove", "↶ 拆除", "返还材料"),
    )

    fun preview(s: BlockIslandState, action: String?, x: Int, y: Int): IslandBuildPreview? {
        if (action == null || recipes.none { it.action == action } || !s.world.inBounds(x, y))
            return null
        val allowed = when (action) {
            "plant" -> s.world.wood > 0 && BlockIslandRules.canPlantAt(s, x, y)
            "remove" -> BlockIslandRules.canRemoveAt(s, x, y)
            else -> BlockIslandRules.canBuild(s, action, x, y)
        }
        return IslandBuildPreview(action, x, y, allowed)
    }

    fun label(action: String?): String =
        recipes.firstOrNull { it.action == action }?.name ?: "建造"
}
