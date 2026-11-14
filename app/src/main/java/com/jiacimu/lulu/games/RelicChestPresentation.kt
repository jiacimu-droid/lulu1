package com.jiacimu.lulu.games

/**
 * The opening ceremony reads a verified physical result. It never creates a loot item,
 * consumes a turn, or changes the authoritative RelicRun.
 */
internal data class RelicChestReceipt(
    val runId: String,
    val chestId: Int,
    val loot: RelicLoot,
    val inBag: Boolean,
)

internal object RelicChestPresentation {
    /** A distant explicit target must not silently redirect the opening action. */
    fun actionableChest(run: RelicRun, selected: Pair<Int, Int>): RelicChest? {
        val hero = run.hero
        val selectedChest = run.chests.firstOrNull { !it.opened &&
            run.visible(it.x, it.y) && it.x == selected.first && it.y == selected.second }
        val candidate = selectedChest ?: run.chests.firstOrNull { !it.opened &&
            run.visible(it.x, it.y) && RelicEscapeRules.distance(hero.x, hero.y, it.x, it.y) <= 1 }
        return candidate?.takeIf { RelicEscapeRules.distance(hero.x, hero.y, it.x, it.y) <= 1 }
    }

    /**
     * Single player-input adapter for the HUD, map selection and D-pad.
     * The rules still own rewards, the ledger still owns persistence, and an AI
     * companion's separate physical action is not silently reinterpreted.
     * With no valid chest the original action reaches the rules' failure reply.
     */
    fun playerAction(run: RelicRun?, action: String, x: Int, y: Int): String {
        if(action!="open" || run==null)return action
        val chest=run.chests.firstOrNull { !it.opened && it.x==x && it.y==y }
            ?:return action
        val steps=RelicEscapeRules.openSteps(chest)
        return if((run.chestProgress[chest.id] ?: 0)<steps-1)"pry" else "open"
    }

    fun isCurrentOpening(run: RelicRun, chestId: Int, receipt: RelicChestReceipt?): Boolean =
        receipt != null && receipt.runId == run.id && receipt.chestId == chestId &&
            run.chests.any { it.id == chestId && it.opened }

    fun committed(before: RelicRun, after: RelicRun, chestId: Int): RelicChestReceipt? {
        if (before.id != after.id) return null
        val unopened = before.chests.firstOrNull { it.id == chestId && !it.opened }
            ?: return null
        if (after.chests.none { it.id == chestId && it.opened }) return null
        if (after.events.none { it.who == "player" && it.action == "open" &&
                it.turn == before.turns }) return null
        val expected = RelicEscapeRules.treasure(before, unopened)
        // A previously owned identical item is not evidence of this opening.
        // Compare the before/after physical inventories; the ceremony cannot
        // claim "in bag" when the new copy actually fell to the floor.
        val bagGain = after.hero.bag.count { it == expected } -
            before.hero.bag.count { it == expected }
        val groundGain = after.drops.count { it.loot == expected &&
            it.x == unopened.x && it.y == unopened.y } -
            before.drops.count { it.loot == expected &&
                it.x == unopened.x && it.y == unopened.y }
        if (bagGain <= 0 && groundGain <= 0) return null
        return RelicChestReceipt(before.id, chestId, expected, bagGain > 0)
    }
}
