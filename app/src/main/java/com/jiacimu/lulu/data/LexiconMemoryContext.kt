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
            // Legacy concern notes are never ACTIVE psychological state;
            // relevant ones may appear only as explicitly labeled history.
        val terms = memorySearchTerms(RelevantMemoryRecall.focusQuery(query))
        val sleepTopic = listOf("睡", "作息", "熬夜", "起床", "休息").any { it in query }
        val ranked = entries.map { entry ->
            val relevance = terms.intersect(memorySearchTerms("${entry.title} ${entry.content}")).size
            val routine = entry.section == LexiconSection.Life &&
                listOf("睡", "作息", "熬夜", "起床", "休息").any {
                    it in (entry.title + entry.content)
                }
            entry to (relevance + if (sleepTopic && routine) 3 else 0)
        }.sortedWith(compareByDescending<Pair<LexiconEntry, Int>> { it.second }.thenByDescending { it.first.updatedAt })
        val active = ranked.filter { it.first.status == LexiconStatus.Active &&
            it.first.section in setOf(LexiconSection.Promise, LexiconSection.Life) }.map { it.first }
        // Living private concerns are recalled from innerLife, never the historical lexicon.
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
