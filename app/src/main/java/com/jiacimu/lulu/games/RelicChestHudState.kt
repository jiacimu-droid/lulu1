package com.jiacimu.lulu.games

/**
 * Presentation-only state. The committed receipt is evidence from the authoritative
 * RelicRun, not a request to grant loot. Closing the animation must not erase it.
 */
internal data class RelicChestHudState(
    val opening: RelicChestReceipt? = null,
    val lastCommitted: RelicChestReceipt? = null,
) {
    fun committed(receipt: RelicChestReceipt): RelicChestHudState =
        RelicChestHudState(opening = receipt, lastCommitted = receipt)

    fun dismiss(receipt: RelicChestReceipt): RelicChestHudState =
        if (opening == receipt) copy(opening = null) else this

    fun nextAction(): RelicChestHudState = RelicChestHudState()
}
