package com.jiacimu.lulu.data

/**
 * Evidence-safe, bounded view for ONE existing batch of complete semantic turns.
 *
 * The raw ledger is never rewritten. A busy game, meeting or phone session
 * may contain hundreds of steps; those are one experience for scheduling but
 * no model is asked to read hundreds of repetitive action receipts.
 * Only IDs actually shown below can be cited by the extractor.
 */
internal object MemoryExtractionTurnView {
    data class Batch(
        val text: String,
        val visibleSources: List<SharedTimelineEvent>,
        val turnCount: Int,
        val rawEventCount: Int,
    )

    fun build(turns: List<RecentSemanticTimeline.Turn>): Batch {
        val visible = linkedMapOf<String, SharedTimelineEvent>()
        val parts = mutableListOf<String>()
        turns.forEachIndexed { index, turn ->
            // Do not let one large game consume the entire 15-turn extraction.
            // Speech is given more room than low-level action receipts.
            val perTurnBudget = when (turn.kind) {
                RecentSemanticTimeline.Kind.SPEECH -> 2_400
                RecentSemanticTimeline.Kind.EXPERIENCE -> 1_600
                RecentSemanticTimeline.Kind.SINGLE -> 1_800
            }
            val excerpt = RecentSemanticTimeline.render(turn, perTurnBudget)
            if (excerpt.isBlank()) return@forEachIndexed
            val cited = turn.events.filter { event ->
                excerpt.contains("eventId=${event.id}@${event.revision}")
            }
            // No valid source ID means no new memory may be derived from it.
            if (cited.isEmpty()) return@forEachIndexed
            cited.forEach { visible[it.id] = it }
            parts.add("【语义轮次 ${index + 1}/${turns.size}｜仅下列真实事件可引用】\n$excerpt")
        }
        return Batch(parts.joinToString("\n"), visible.values.toList(),
            turns.size, turns.sumOf { it.events.size })
    }
}
