package com.jiacimu.lulu.ai

/**
 * Prefix reuse without losing any live context.
 * TOP/NORMAL/BOTTOM worldbook placement is preserved. The only memory
 * promoted to the stable prefix is the explicit, always-delivered core.
 */
internal object PromptCacheSectionOrder {
    fun arrange(
        topWorldBooks: List<Pair<String, String>>,
        fixedSections: List<Pair<String, String>>,
        normalWorldBooks: List<Pair<String, String>>,
        memorySections: List<Pair<String, String>>,
        liveScene: List<Pair<String, String>>,
        dynamicTail: List<Pair<String, String>>,
        bottomWorldBooks: List<Pair<String, String>>,
    ): List<Pair<String, String>> {
        val core = memorySections.filter { it.first == "核心记忆" }
        val otherMemory = memorySections.filterNot { it.first == "核心记忆" }
        return topWorldBooks + fixedSections + normalWorldBooks + core +
            liveScene + otherMemory + dynamicTail + bottomWorldBooks
    }
}
