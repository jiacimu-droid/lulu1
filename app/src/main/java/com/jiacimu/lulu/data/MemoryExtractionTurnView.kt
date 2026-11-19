package com.jiacimu.lulu.data

/**
 * Evidence-safe, bounded view for an existing batch of COMPLETE semantic turns.
 * Raw source IDs/revisions are authoritative; no synthetic memories or stores.
 *
 * Game/tool episodes may contain hundreds of repetitive action receipts. Their
 * action previews are intentionally compact, but every actual conversation
 * and independent factual statement is either shown completely or LEFT
 * UNPROCESSED. Never cite a cropped user correction as a finished extraction.
 */
internal object MemoryExtractionTurnView {
    private const val MAX_BATCH_CHARS = 48_000

    data class Batch(
        val text: String,
        val visibleSources: List<SharedTimelineEvent>,
        val turnCount: Int,
        val rawEventCount: Int,
    )

    private data class Shown(val text: String, val sources: List<SharedTimelineEvent>)

    private fun shown(turn: RecentSemanticTimeline.Turn): Shown {
        if (turn.kind == RecentSemanticTimeline.Kind.EXPERIENCE) {
            val excerpt = RecentSemanticTimeline.render(turn, 1_600)
            return Shown(excerpt, turn.events.filter { e ->
                excerpt.contains("eventId=${e.id}@${e.revision}")
            })
        }
        // For actual dialogue or independent evidence, source completeness is
        // more important than a guessed per-bubble token allowance. Each real
        // source's original text is sent IN FULL; the overall request budget is
        // enforced by fit() rather than silently truncating an event.
        val items = turn.events.map { e ->
            val qualifier = if (e.source == "presence") {
                "【本人当时的私人状态/心声，不是对外发言或当下已发生的行动】"
            } else ""
            "[${e.occurredAt}] [${e.channel}] [${e.evidenceKind}]" +
                " eventId=${e.id}@${e.revision} ${e.speaker}：$qualifier${e.evidenceContent}"
        }
        return Shown(items.joinToString("\n"), turn.events)
    }

    /**
     * Take the earliest WHOLE turns that safely fit a model request. A long
     * voice transcript never gets marked extracted merely because its first
     * 2,400 characters were supplied. If a single original turn exceeds the
     * allowed input, let the caller run the span-based extraction path.
     */
    fun fit(turns: List<RecentSemanticTimeline.Turn>, allowEmpty: Boolean = false): List<RecentSemanticTimeline.Turn> {
        val result = mutableListOf<RecentSemanticTimeline.Turn>()
        var chars = 0
        turns.forEach { turn ->
            val size = shown(turn).text.length + 110
            if (chars + size > MAX_BATCH_CHARS) {
                check(allowEmpty || result.isNotEmpty()) {
                    "单个真实发言超出记忆提取输入上限，原文已保留，未标记整理完成"
                }
                return result
            }
            result.add(turn)
            chars += size
        }
        return result
    }

    /** Cover an exceptional long utterance in bounded original spans. The caller
     * checkpoints the whole turn only after every span is processed. */
    fun splitLongTurn(turn: RecentSemanticTimeline.Turn): Sequence<Batch> = sequence {
        for (event in turn.events) {
            val original = event.evidenceContent
            val spanSize = 24_000
            for (start in original.indices step spanSize) {
                val end = minOf(start + spanSize, original.length)
                val heading = "[${event.occurredAt}] [${event.channel}] " +
                    "eventId=${event.id}@${event.revision} ${event.speaker} " +
                    "【同一原文字符 ${start + 1}–$end/${original.length}】"
                yield(Batch("$heading\n" + original.substring(start, end), listOf(event), 1, 1))
            }
        }
    }

    fun build(turns: List<RecentSemanticTimeline.Turn>): Batch {
        val visible = linkedMapOf<String, SharedTimelineEvent>()
        val parts = mutableListOf<String>()
        turns.forEachIndexed { index, turn ->
            val display = shown(turn)
            if (display.text.isBlank()) return@forEachIndexed
            if (display.sources.isEmpty()) return@forEachIndexed
            display.sources.forEach { visible[it.id] = it }
            parts.add("【语义轮次 ${index + 1}/${turns.size}｜仅下列真实事件可引用】\n${display.text}")
        }
        val text = parts.joinToString("\n")
        check(text.length <= MAX_BATCH_CHARS + turns.size * 30) {
            "记忆提取批次超出输入上限，不消费未完整读取的原始事件"
        }
        return Batch(text, visible.values.toList(),
            turns.size, turns.sumOf { it.events.size })
    }
}
