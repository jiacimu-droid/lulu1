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
