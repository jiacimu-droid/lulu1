package com.jiacimu.lulu.data

internal data class RenderedMemorySection(val text: String = "", val ids: List<String> = emptyList(),
                                         val truncatedIds: List<String> = emptyList())

/** Ranked hits first, then exact adjacent evidence, finally chronological display. */
internal object MemoryEvidenceSelection {
    fun expand(seeds: List<SharedTimelineEvent>, live: List<SharedTimelineEvent>, limit: Int = 32): List<SharedTimelineEvent> =
        NarrativeEvidenceChain.expand(seeds, live, limit)

    fun render(events: List<SharedTimelineEvent>, characterBudget: Int): RenderedMemorySection {
        if (characterBudget <= 0) return RenderedMemorySection()
        var remaining = characterBudget
        val kept = mutableListOf<Pair<SharedTimelineEvent, String>>()
        val truncated = mutableListOf<String>()
        val episodeHints = NarrativeEvidenceChain.annotations(events)
        events.distinctBy { it.id }.forEach { event ->
            // Same UTC-trap as recalled summaries: a raw instant reads 8
            // hours off local time. Render local readable time instead.
            val at = java.time.format.DateTimeFormatter.ofPattern("M月d日 H:mm")
                .format(event.occurredAt.atZone(java.time.ZoneId.of("Asia/Shanghai")))
            val prefix = "[$at] [${event.channel}] ${event.speaker}${episodeHints[event.id].orEmpty()}："
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

