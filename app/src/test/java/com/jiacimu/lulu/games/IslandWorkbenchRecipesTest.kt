package com.jiacimu.lulu.games

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Craft buttons and the actual island executor must agree about cost and proximity. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class IslandWorkbenchRecipesTest {
    @Test fun contextualRecipesChargeExactMaterialsAndCannotCraftTwice() {
        val base = BlockIslandRules.newWorld(427, emptyList())
        val w = base.world.copy(x = 5, y = 5, wood = 20, stone = 20, mobs = emptyList())
        val table = w.index(6, 5)
        val ready = base.copy(world = w, structures = mapOf(table to "workbench"))

        assertEquals(setOf("axe", "pickaxe", "sword", "rod"),
            IslandWorkbenchRecipes.all.map { it.tool }.toSet())
        for (recipe in IslandWorkbenchRecipes.all) {
            assertEquals("craft_" + recipe.tool, recipe.action)
            assertSame(recipe, IslandWorkbenchRecipes.find(recipe.tool))
            assertTrue(IslandWorkbenchRecipes.canCraft(ready, recipe))
            val made = BlockIslandRules.act(ready, recipe.action, w.x, w.y)
            assertEquals(w.wood - recipe.wood, made.world.wood)
            assertEquals(w.stone - recipe.stone, made.world.stone)
            assertTrue(recipe.tool in made.tools)
            assertFalse(IslandWorkbenchRecipes.canCraft(made, recipe))
            val repeat = BlockIslandRules.act(made, recipe.action, w.x, w.y)
            assertEquals(made.world.turns, repeat.world.turns)
            assertEquals(made.world.wood, repeat.world.wood)
            assertEquals(made.world.stone, repeat.world.stone)

            val shortWood = ready.copy(world = w.copy(wood = recipe.wood - 1))
            val shortStone = ready.copy(world = w.copy(stone = recipe.stone - 1))
            assertFalse(IslandWorkbenchRecipes.canCraft(shortWood, recipe))
            assertFalse(IslandWorkbenchRecipes.canCraft(shortStone, recipe))
            assertEquals(shortWood.world.turns,
                BlockIslandRules.act(shortWood, recipe.action, w.x, w.y).world.turns)
            assertEquals(shortStone.world.turns,
                BlockIslandRules.act(shortStone, recipe.action, w.x, w.y).world.turns)
        }
        assertNull(IslandWorkbenchRecipes.find("unknown"))
    }

    @Test fun craftingRequiresNearbyWorkbenchNotJustAnInventoryMenu() {
        val base = BlockIslandRules.newWorld(102, emptyList())
        val world = base.world.copy(x = 5, y = 5, wood = 20, stone = 20, mobs = emptyList())
        val table = world.index(6, 5)
        val noBench = base.copy(world = world)
        val farAway = noBench.copy(structures = mapOf(world.index(19, 14) to "workbench"))
        val close = noBench.copy(structures = mapOf(table to "workbench"))
        IslandWorkbenchRecipes.all.forEach { recipe ->
            assertFalse(IslandWorkbenchRecipes.canCraft(noBench, recipe))
            assertFalse(IslandWorkbenchRecipes.canCraft(farAway, recipe))
            assertTrue(IslandWorkbenchRecipes.canCraft(close, recipe))
            assertEquals(world.turns,
                BlockIslandRules.act(noBench, recipe.action, world.x, world.y).world.turns)
            assertEquals(world.turns,
                BlockIslandRules.act(farAway, recipe.action, world.x, world.y).world.turns)
        }
    }
}
