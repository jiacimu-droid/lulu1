package com.jiacimu.lulu.data

internal data class RenderedMemorySection(val text: String = "", val ids: List<String> = emptyList(),
                                         val truncatedIds: List<String> = emptyList())

/** Ranked hits first, then exact adjacent evidence, finally chronological display. */
internal object MemoryEvidenceSelection {
    fun expand(seeds: List<SharedTimelineEvent>, live: List<SharedTimelineEvent>, limit: Int = 32): List<SharedTimelineEvent> =
        NarrativeEvidenceChain.expand(seeds, live, limit)

    fun render(events: List<SharedTimelineEvent>, characterBudget: Int,
               focus: String = "", zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): RenderedMemorySection {
        if (characterBudget <= 0) return RenderedMemorySection()
        var remaining = characterBudget
        val kept = mutableListOf<Pair<SharedTimelineEvent, String>>()
        val truncated = mutableListOf<String>()
        val episodeHints = NarrativeEvidenceChain.annotations(events)
        events.distinctBy { it.id }.forEach { event ->
            // Preserve the year and local UTC offset on historical evidence.
            // A fixed Shanghai zone and yearless "2:00" made yesterday's
            // night conversation look like the current local time elsewhere.
            val at = java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME
                .format(event.occurredAt.atZone(zone))
            val basePrefix = "[" + at + "] [" + event.channel + "] " + event.speaker + "："
            val note = episodeHints[event.id].orEmpty()
            val plainPrefix = if (remaining - basePrefix.length - note.length - 1 >= 48)
                basePrefix.dropLast(1) + note + "：" else basePrefix
            val content = event.evidenceContent
            // Rank original ranges by relevance, but render every range that
            // actually fits this request. Previously firstOrNull() silently
            // discarded distant conditions in the same long event, even when
            // the caller left enough evidence budget to include them.
            val spans = if (content.length <= 1_200)
                listOf(OriginalEvidenceSpan(0, content.length, content, 0))
            else OriginalEvidenceSpans.ranked(content, focus, maxSpans = 4)
            if (spans.isEmpty()) return@forEach
            val selectedLines = mutableListOf<Pair<Int, String>>()
            if (content.length <= 1_200) {
                val space = remaining - plainPrefix.length - 1
                if (space < 48) return@forEach
                val line = plainPrefix + if (content.length <= minOf(space, 1_200)) content
                    else content.take(minOf(space, 1_200) - 1) + "…"
                selectedLines += 0 to line
                if (line.endsWith("…")) truncated += event.id
            } else {
                val staticPrefix = basePrefix.dropLast(1) + " eventId=" +
                    event.id + "@" + event.revision + " "
                var used = 0
                for (span in spans) {
                    val available = remaining - used - staticPrefix.length - 72
                    if (available < 48) break
                    val excerpt = span.text.take(minOf(available, 900))
                    val range = "【原文字符" + (span.start + 1) + "-" +
                        (span.start + excerpt.length) + "/" + content.length +
                        "；其余未注入，勿视为连续】："
                    val line = staticPrefix + range + excerpt
                    if (used + line.length + 1 > remaining) break
                    selectedLines += span.start to line
                    used += line.length + 1
                }
                // Even after several exact excerpts the full original is
                // still authoritative; the omitted parts must not be inferred.
                if (selectedLines.isNotEmpty()) truncated += event.id
            }
            if (selectedLines.isEmpty()) return@forEach
            kept += event to selectedLines.sortedBy { it.first }
                .joinToString("\n") { it.second }
            remaining -= selectedLines.sumOf { it.second.length + 1 }
        }
        return RenderedMemorySection(kept.sortedBy { it.first.occurredAt }.joinToString("\n") { it.second },
            kept.map { it.first.id }, truncated)
    }
}
