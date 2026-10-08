package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.LexiconStatus

object LexiconMemoryContext {
    fun select(characterId: String, query: String): List<LexiconEntry> {
        val entries = LuluRepositories.lexicon.snapshot(characterId)
        val active = entries.filter { it.status == LexiconStatus.Active && it.section in setOf(LexiconSection.Promise, LexiconSection.Concern, LexiconSection.Life) }
        // Live concerns and responsibilities have their own channel; diaries cannot evict them.
        val terms = memorySearchTerms(RelevantMemoryRecall.focusQuery(query))
        var remaining = 7_000
        val related = entries.filterNot { it in active }.map { entry ->
            entry to terms.intersect(memorySearchTerms("${entry.title} ${entry.content}")).size
        }.filter { it.second >= 2 }
            .sortedWith(compareByDescending<Pair<LexiconEntry, Int>> { it.second }.thenByDescending { it.first.updatedAt })
            .map { it.first }.filter { entry ->
                val cost = entry.title.length + entry.content.length + 60
                if (cost <= remaining) { remaining -= cost; true } else false
            }.take(96)
        return active + related
    }
}
