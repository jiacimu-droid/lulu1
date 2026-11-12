package com.jiacimu.lulu.games

/** One catalog for both the actual crafting rules and the in-world workbench UI. */
internal data class IslandToolRecipe(
    val tool: String,
    val title: String,
    val wood: Int,
    val stone: Int,
    val effect: String,
    val crystals: Int = 0,
) {
    val action: String get() = "craft_" + tool
    val description: String get() = "$title · ${wood}木${stone}石" +
        (if(crystals>0)"${crystals}晶石" else "") + " · $effect"
}

internal object IslandWorkbenchRecipes {
    val all: List<IslandToolRecipe> = listOf(
        IslandToolRecipe("axe", "木斧", 2, 1, "伐木4木/次；徒手仅2木"),
        IslandToolRecipe("pickaxe", "石镐", 2, 2, "采矿4石/次，能开采蓝色晶矿"),
        IslandToolRecipe("sword", "石剑", 1, 3, "伤害4"),
        IslandToolRecipe("rod", "鱼竿", 2, 1, "靠水边钓鱼，篝火可烤鱼"),
        IslandToolRecipe("charm", "晶石护符", 1, 1, "致命伤时碎裂救命一次", crystals=1),
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
            world.wood >= recipe.wood && world.stone >= recipe.stone &&
            state.crystals >= recipe.crystals
    }
}
