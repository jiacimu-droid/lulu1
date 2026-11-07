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
        val inBag = after.hero.bag.any { it == expected }
        val onGround = after.drops.any { it.loot == expected &&
            it.x == unopened.x && it.y == unopened.y }
        if (!inBag && !onGround) return null
        return RelicChestReceipt(before.id, chestId, expected, inBag)
    }
}
