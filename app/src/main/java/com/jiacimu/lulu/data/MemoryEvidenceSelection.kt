package com.jiacimu.lulu.data

internal data class RenderedMemorySection(val text: String = "", val ids: List<String> = emptyList(),
                                         val truncatedIds: List<String> = emptyList())

/** Ranked hits first, then exact adjacent evidence, finally chronological display. */
internal object MemoryEvidenceSelection {
    fun expand(seeds: List<SharedTimelineEvent>, live: List<SharedTimelineEvent>, limit: Int = 32): List<SharedTimelineEvent> =
        NarrativeEvidenceChain.expand(seeds, live, limit)

    fun render(events: List<SharedTimelineEvent>, characterBudget: Int,
               focus: String = ""): RenderedMemorySection {
        if (characterBudget <= 0) return RenderedMemorySection()
        var remaining = characterBudget
        val kept = mutableListOf<Pair<SharedTimelineEvent, String>>()
        val truncated = mutableListOf<String>()
        val episodeHints = NarrativeEvidenceChain.annotations(events)
        events.distinctBy { it.id }.forEach { event ->
            val at = java.time.format.DateTimeFormatter.ofPattern("M月d日 H:mm")
                .format(event.occurredAt.atZone(java.time.ZoneId.of("Asia/Shanghai")))
            val basePrefix = "[" + at + "] [" + event.channel + "] " + event.speaker + "："
            val note = episodeHints[event.id].orEmpty()
            val plainPrefix = if (remaining - basePrefix.length - note.length - 1 >= 48)
                basePrefix.dropLast(1) + note + "：" else basePrefix
            val content = event.evidenceContent.trim().replace("\n", " ")
            val span = OriginalEvidenceSpans.ranked(content, focus, maxSpans = 4).firstOrNull()
                ?: return@forEach
            val partial = span.start > 0 || span.endExclusive < content.length
            val line = if (!partial) {
                val space = remaining - plainPrefix.length - 1
                if (space < 48) return@forEach
                plainPrefix + if (content.length <= minOf(space, 1_200)) content
                    else content.take(minOf(space, 1_200) - 1) + "…"
            } else {
                val staticPrefix = basePrefix.dropLast(1) + " eventId=" +
                    event.id + "@" + event.revision + " "
                // Reserve range-label space before selecting the exact original excerpt.
                val available = remaining - staticPrefix.length - 72
                if (available < 48) return@forEach
                val excerpt = span.text.take(minOf(available, 900))
                val range = "【原文字符" + (span.start + 1) + "-" +
                    (span.start + excerpt.length) + "/" + content.length +
                    "；其余未注入，勿视为连续】："
                val rendered = staticPrefix + range + excerpt
                if (rendered.length + 1 > remaining) return@forEach
                rendered
            }
            if (partial || line.endsWith("…")) truncated += event.id
            kept += event to line
            remaining -= line.length + 1
        }
        return RenderedMemorySection(kept.sortedBy { it.first.occurredAt }.joinToString("\n") { it.second },
            kept.map { it.first.id }, truncated)
    }
}
