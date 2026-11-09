package com.jiacimu.lulu.games

/** One catalog for both the actual crafting rules and the in-world workbench UI. */
internal data class IslandToolRecipe(
    val tool: String,
    val title: String,
    val wood: Int,
    val stone: Int,
    val effect: String,
) {
    val action: String get() = "craft_" + tool
    val description: String get() = "$title · ${wood}木${stone}石 · $effect"
}

internal object IslandWorkbenchRecipes {
    val all: List<IslandToolRecipe> = listOf(
        IslandToolRecipe("axe", "木斧", 2, 1, "伐木+2"),
        IslandToolRecipe("pickaxe", "石镐", 2, 2, "采矿+2"),
        IslandToolRecipe("sword", "石剑", 1, 3, "伤害4"),
        IslandToolRecipe("rod", "鱼竿", 2, 1, "可钓鱼"),
    )
    fun find(tool: String): IslandToolRecipe? = all.firstOrNull { it.tool == tool }
    fun canCraft(state: BlockIslandState, recipe: IslandToolRecipe): Boolean {
        val world = state.world
        val workbenchNear = state.structures.any { (location, kind) ->
            kind == "workbench" &&
                kotlin.math.abs(world.x - location % world.width) +
                kotlin.math.abs(world.y - location / world.width) <= 2
        }
        return workbenchNear && recipe.tool !in state.tools &&
            world.wood >= recipe.wood && world.stone >= recipe.stone
    }
}
