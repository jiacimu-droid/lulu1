package com.jiacimu.lulu.data

import java.time.Instant

/** Read-only attention: retain one recent cue and rotate older themes within the existing budget. */
internal object CharacterAttentionWorkingSet {
    fun <T> select(items: List<T>, limit: Int, now: Instant, cadenceSeconds: Long,
                   identity: (T) -> String, theme: (T) -> String): List<T> {
        if (limit <= 0) return emptyList()
        val unique = items.distinctBy(identity)
        if (unique.size <= limit) return unique
        val newest = unique.last()
        val older = unique.dropLast(1).sortedBy(identity)
        val slot = Math.floorDiv(now.epochSecond, cadenceSeconds.coerceAtLeast(1L))
        val offset = Math.floorMod(slot, older.size.toLong()).toInt()
        val rotated = older.drop(offset) + older.take(offset)
        val selected = mutableListOf(newest)
        val themes = mutableSetOf(theme(newest))
        for (item in rotated) {
            if (selected.size == limit) break
            if (themes.add(theme(item))) selected.add(item)
        }
        for (item in rotated) {
            if (selected.size == limit) break
            if (selected.none { identity(it) == identity(item) }) selected.add(item)
        }
        return selected
    }
}
