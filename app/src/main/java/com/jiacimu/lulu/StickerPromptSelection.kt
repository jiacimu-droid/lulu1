package com.jiacimu.lulu

/** Keep imported/favorite and context-relevant images visible without hiding whole later packs. */
internal fun selectPromptStickers(items: List<LuluSticker>, limit: Int, cues: String): List<LuluSticker> {
    val budget = limit.coerceIn(0, 80)
    if (budget == 0) return emptyList()
    val clean = cues.takeLast(600).lowercase().filter(Char::isLetterOrDigit)
    val terms = clean.windowed(2).toSet()
    fun score(sticker: LuluSticker): Int {
        val words = (sticker.name + sticker.usageHint).lowercase().filter(Char::isLetterOrDigit)
        val relevant = if (terms.isEmpty()) 0 else words.windowed(2).toSet().count { it in terms }.coerceAtMost(20)
        return relevant * 3 + (if (sticker.favorite) 20 else 0) + (if (sticker.pack == "我的添加") 5 else 0)
    }
    val ranked = items.distinctBy(LuluSticker::id).sortedByDescending(::score)
    val result = ranked.take((budget / 2).coerceAtLeast(1)).toMutableList()
    val picked = result.mapTo(mutableSetOf(), LuluSticker::id)
    val packs = ranked.filterNot { it.id in picked }.groupBy(LuluSticker::pack).values.map { it.toMutableList() }
    while (result.size < budget) {
        var added = false
        for (pack in packs) {
            if (result.size >= budget) break
            if (pack.isNotEmpty()) {
                val next = pack.removeAt(0)
                if (picked.add(next.id)) result += next
                added = true
            }
        }
        if (!added) break
    }
    return result
}
