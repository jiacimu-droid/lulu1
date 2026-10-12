package com.jiacimu.lulu.data

import java.time.Duration

internal data class RenderedMemorySection(val text: String = "", val ids: List<String> = emptyList(),
                                         val truncatedIds: List<String> = emptyList())

/** Ranked hits first, then exact adjacent evidence, finally chronological display. */
internal object MemoryEvidenceSelection {
    fun expand(seeds: List<SharedTimelineEvent>, live: List<SharedTimelineEvent>, limit: Int = 32): List<SharedTimelineEvent> {
        if (limit <= 0) return emptyList()
        val selected = linkedMapOf<String, SharedTimelineEvent>()
        seeds.take(limit).forEach { selected[it.id] = it }
        val sessions = live.filter { it.sessionId.isNotBlank() }.groupBy { Triple(it.sessionId, it.characterId, it.channel) }
        seeds.take(8).forEach { seed ->
            if (seed.sessionId.isBlank()) return@forEach
            val events = sessions[Triple(seed.sessionId, seed.characterId, seed.channel)].orEmpty().sortedBy { it.occurredAt }
            val index = events.indexOfFirst { it.id == seed.id }
            if (index < 0) return@forEach
            listOf(index - 1, index + 1).mapNotNull(events::getOrNull).forEach { neighbor ->
                if (selected.size < limit && neighbor.characterId == seed.characterId &&
                    neighbor.channel == seed.channel &&
                    Duration.between(seed.occurredAt, neighbor.occurredAt).abs().seconds <= 900) {
                    selected.putIfAbsent(neighbor.id, neighbor)
                }
            }
        }
        return selected.values.toList()
    }

    fun render(events: List<SharedTimelineEvent>, characterBudget: Int): RenderedMemorySection {
        if (characterBudget <= 0) return RenderedMemorySection()
        var remaining = characterBudget
        val kept = mutableListOf<Pair<SharedTimelineEvent, String>>()
        val truncated = mutableListOf<String>()
        events.distinctBy { it.id }.forEach { event ->
            val prefix = "[${event.occurredAt}] [${event.channel}] ${event.speaker}："
            val space = remaining - prefix.length - 1
            if (space < 48) return@forEach
            val content = event.evidenceContent.trim().replace("\n", " ")
            val allowance = minOf(space, 1_200)
            val shown = if (content.length <= allowance) content else content.take(allowance - 1) + "…"
            if (shown.length < content.length) truncated += event.id
            kept += event to (prefix + shown)
            remaining -= prefix.length + shown.length + 1
        }
        return RenderedMemorySection(kept.sortedBy { it.first.occurredAt }.joinToString("\n") { it.second },
            kept.map { it.first.id }, truncated)
    }
}
