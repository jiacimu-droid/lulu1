package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.LexiconStatus

object LexiconMemoryContext {
    fun select(characterId: String, query: String): List<LexiconEntry> {
        val taskEntries = CommitmentTaskStore.active(characterId).mapNotNullTo(mutableSetOf()) { it.lexiconEntryId }
        val entries = LuluRepositories.lexicon.snapshot(characterId)
            .filter { lexiconHasLiveSources(it) }
            .filterNot { it.id in taskEntries }
        val terms = memorySearchTerms(RelevantMemoryRecall.focusQuery(query))
        val ranked = entries.map { entry ->
            entry to terms.intersect(memorySearchTerms("${entry.title} ${entry.content}")).size
        }.sortedWith(compareByDescending<Pair<LexiconEntry, Int>> { it.second }.thenByDescending { it.first.updatedAt })
        val active = ranked.filter { it.first.status == LexiconStatus.Active &&
            it.first.section in setOf(LexiconSection.Promise, LexiconSection.Concern, LexiconSection.Life) }.map { it.first }
        // Live concerns and responsibilities have their own channel; diaries cannot evict them.
        var remaining = 7_000
        val related = ranked.filter { it.first !in active && it.second >= 2 }
            .map { it.first }.filter { entry ->
                val cost = entry.title.length + entry.content.length + 60
                if (cost <= remaining) { remaining -= cost; true } else false
            }.take(96)
        return buildList {
            active.firstOrNull()?.let(::add)
            for (index in 0 until maxOf(active.size - 1, related.size).coerceAtLeast(0)) {
                related.getOrNull(index)?.let(::add)
                active.getOrNull(index + 1)?.let(::add)
            }
        }
    }
}

internal fun lexiconHasLiveSources(entry: LexiconEntry): Boolean {
    val live = SharedExperienceTimeline.eventsByIds(entry.characterId, entry.sourceEventIds).associateBy { it.id }
    return entry.sourceEventIds.all { id -> live[id]?.let { event ->
        entry.sourceRevisions[id]?.let { it == event.revision } ?: true
    } == true }
}
