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

    /** Failed world actions do not invalidate evidence of the last committed chest. */
    fun afterWorldAction(committed: Boolean): RelicChestHudState =
        if (committed) RelicChestHudState() else this

    /** A successful save can still contain a rejected/no-op tap with no world turn. */
    fun afterWorldTurn(before: Int?, after: Int?): RelicChestHudState =
        afterWorldAction(committed = before != null && after != null && after != before)

    fun nextAction(): RelicChestHudState = afterWorldAction(committed = true)
}
