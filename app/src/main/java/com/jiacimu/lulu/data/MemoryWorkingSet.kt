package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.qqChatImageContextText
import com.jiacimu.lulu.core.LexiconSection

internal object MemoryWorkingSet {
    const val RECENT = "近期原始时间线（新原话优先；旧理解不得覆盖本轮纠正）："
    const val EVIDENCE = "召回的原始依据（按证据类型理解）："
    const val SUMMARY = "往事与当前理解（摘要仅供联想，以原始依据为准）："
    const val LEXICON = "生活记录（日记和收藏只证明写作或收藏；旧版挂心笔记不进入当前心理决策）："

    fun assemble(context: UnifiedMemoryContext, lexicon: List<LexiconEntry>,
                 auxiliary: List<Pair<String, String>>, budget: PromptContextBudget,
                 coreSection: String): MemoryWorkingSelection {
        // Old callers may supply only a single-role lexicon with an empty
        // UnifiedMemoryContext. Preserve that path, but never merge diary
        // entries from two characters when no owner was selected.
        val lexiconOwners = lexicon.map { it.characterId }.filter(String::isNotBlank).distinct()
        val owner = context.effectiveOwner.ifBlank {
            lexiconOwners.singleOrNull().orEmpty()
        }
        val candidates = buildList {
            // Full-mode gateways render this working set rather than the
            // standalone memorySection. Deliver the SAME recall guidance here,
            // within the existing budget and injection audit.
            context.recallUseGuidance.takeIf(String::isNotBlank)?.let { guidance ->
                add(MemoryPromptCandidate("recall-use-guidance", "回忆与当前对话：", guidance, 900))
            }
            val episodeHints = NarrativeEvidenceChain.annotations(
                context.ownedRecentEvents + context.ownedSourceEvents)
            fun evidence(event: SharedTimelineEvent, recent: Boolean) {
                val content = event.evidenceContent
                val section = if (recent) RECENT else EVIDENCE
                val basePriority = if (recent) 950 else 800
                val identity = (if (recent) "recent:" else "event:") + event.id
                OriginalEvidenceSpans.ranked(content, context.retrievalFocus).forEach { span ->
                    val complete = span.start == 0 && span.endExclusive == content.length
                    val range = if (complete) "" else "【原文片段字符" +
                        (span.start + 1) + "-" + span.endExclusive + "/" + content.length +
                        "；其余未注入，不能把不同片段视为连续原话】"
                    add(MemoryPromptCandidate(
                        if (complete) identity else identity + "@" + event.revision +
                            ":" + span.start + "-" + span.endExclusive,
                        section,
                        "- [" + event.occurredAt + "] [" + event.channel + "] [" +
                            event.evidenceKind + "] eventId=" + event.id + "@" + event.revision +
                            " " + event.speaker + episodeHints[event.id].orEmpty() + "：" +
                            range + span.text,
                        basePriority + span.relevance.coerceAtMost(12) * 8 +
                            if (span.start == 0) 4 else 0,
                        payload = span.text, sources = setOf(event.id),
                        originalEvidence = complete,
                    ))
                }
            }
            // Reverse only the working view: recent corrections must win budget pressure.
            // A recalled event may also be inside the recent window. Keep the
            // newest original once, rather than charging for the same event twice.
            val deliveredEventIds = mutableSetOf<String>()
            context.ownedRecentEvents.asReversed().forEach { event ->
                if (deliveredEventIds.add(event.id)) evidence(event, true)
            }
            context.ownedSourceEvents.forEach { event ->
                if (deliveredEventIds.add(event.id)) evidence(event, false)
            }
            addAll(MemoryWorkingSetPolicy.auxiliaryCandidates(auxiliary))
            context.ownedMemories.forEach { memory ->
                add(MemoryPromptCandidate("memory:${memory.id}", SUMMARY,
                    "- [${memory.occurredAt ?: memory.createdAt}] [${CharacterAssociativeRecall.memoryLane(memory).label}] memoryId=${memory.id} ${memory.content}" +
                        if (memory.kind == com.jiacimu.lulu.core.MemoryKind.Emotion) "（当时的情绪印象，不代表此刻状态）" else "",
                    650, payload = memory.content, sources = memory.sourceIds().toSet(),
                    derivedSummary = memory.kind != com.jiacimu.lulu.core.MemoryKind.Emotion))
            }
            lexicon.filter { owner.isNotBlank() && it.characterId == owner }
                .forEachIndexed { index, entry ->
                if (entry.section == LexiconSection.Concern) {
                    add(MemoryPromptCandidate("lexicon:${entry.id}", LEXICON,
                        "- [旧版关心笔记/仅供历史参考，非当前挂心、非用户现状、非行动指令] ${entry.title}：${entry.content}",
                        360 - index.coerceAtMost(80), payload = entry.content,
                        sources = entry.sourceEventIds.toSet(), derivedSummary = true))
                    return@forEachIndexed
                }
                val subjective = entry.section in setOf(LexiconSection.Diary, LexiconSection.Favorite)
                // The lexicon retains the exact image attachment for visual playback,
                // while a character's model receives its meaning, not its base64 transport token.
                val readableContent = if (entry.section == LexiconSection.Favorite) {
                    val imageContext = qqChatImageContextText(entry.content)
                    if (imageContext != null) {
                        val reason = entry.content.substringAfter("\n\n收藏理由：", "").take(180)
                        imageContext + if (reason.isNotBlank()) "\n收藏理由：" + reason else ""
                    } else if (entry.content.contains("⟪CHAT_IMAGE:")) {
                        "[收藏的聊天图片，媒体信息不完整]"
                    } else entry.content
                } else entry.content
                add(MemoryPromptCandidate("lexicon:${entry.id}", LEXICON,
                    if (entry.section == LexiconSection.Life)
                        "- [真实生活近况/收录于${entry.updatedAt}，不代表每日习惯] ${entry.title} [${entry.status}] entryId=${entry.id}：${entry.content}；只有新旧时间及事实明确时才自然比较变化，不臆测结果"
                    else "- ${entry.section}/${entry.title} [${entry.status}] entryId=${entry.id}：${readableContent}",
                    740 - index.coerceAtMost(80),
                    payload = readableContent, sources = entry.sourceEventIds.toSet(), derivedSummary = !subjective))
            }
        }
        val protected = listOf(coreSection, context.activeTaskContext).filter(String::isNotBlank)
            .joinToString("\n\n").length.let { if (it == 0) 0 else it + 2 }
        val result = MemoryWorkingSetPolicy.select(candidates, budget.workingSetCharacters, protected,
            mapOf(RECENT to budget.recentCharacters, EVIDENCE to budget.evidenceCharacters,
                SUMMARY to budget.memoryCharacters, LEXICON to budget.lexiconCharacters))
        // Selection is still a draft: the model request may fail or the relay
        // may compact older evidence before transmission. Only the gateway
        // records the final injected IDs AFTER a successful provider response.
        return result
    }
}
