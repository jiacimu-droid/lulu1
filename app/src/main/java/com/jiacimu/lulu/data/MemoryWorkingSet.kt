package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconSection

internal object MemoryWorkingSet {
    const val RECENT = "近期原始时间线（新原话优先；旧理解不得覆盖本轮纠正）："
    const val EVIDENCE = "召回的原始依据（按证据类型理解）："
    const val SUMMARY = "往事与当前理解（摘要仅供联想，以原始依据为准）："
    const val LEXICON = "生活记录（日记和收藏只证明写作或收藏；旧版挂心笔记不进入当前心理决策）："

    fun assemble(context: UnifiedMemoryContext, lexicon: List<LexiconEntry>,
                 auxiliary: List<Pair<String, String>>, budget: PromptContextBudget,
                 coreSection: String): MemoryWorkingSelection {
        val candidates = buildList {
            fun evidence(event: SharedTimelineEvent, recent: Boolean) {
                val content = event.evidenceContent.trim()
                // A 1,200-character preview used to hide useful details even
                // when the unified memory budget had room. Allow a fuller
                // view without changing the durable original event.
                val complete = content.length <= 3_000
                val shown = if (complete) content else
                    content.take(2_200) +
                        "\n【原始事件过长：中段未注入，请凭 eventId 查原始时间线；前后内容不能直接视为连续】\n" +
                        content.takeLast(650)
                add(MemoryPromptCandidate("${if (recent) "recent" else "event"}:${event.id}",
                    if (recent) RECENT else EVIDENCE,
                    "- [${event.occurredAt}] [${event.channel}] [${event.evidenceKind}] eventId=${event.id}@${event.revision} ${event.speaker}：$shown",
                    if (recent) 950 else 800, payload = content, sources = setOf(event.id),
                    originalEvidence = complete))
            }
            // Reverse only the working view: recent corrections must win budget pressure.
            // A recalled event may also be inside the recent window. Keep the
            // newest original once, rather than charging for the same event twice.
            val deliveredEventIds = mutableSetOf<String>()
            context.recentEvents.asReversed().forEach { event ->
                if (deliveredEventIds.add(event.id)) evidence(event, true)
            }
            context.sourceEvents.forEach { event ->
                if (deliveredEventIds.add(event.id)) evidence(event, false)
            }
            auxiliary.forEachIndexed { group, (label, text) ->
                // Complete lines are units. Never cut a negation, condition or evidence ID in half.
                text.lineSequence().filter(String::isNotBlank).forEachIndexed { index, line ->
                    add(MemoryPromptCandidate("state:$group:$index", label, line, 900 - group * 35))
                }
            }
            context.memories.forEach { memory ->
                add(MemoryPromptCandidate("memory:${memory.id}", SUMMARY,
                    "- [${memory.occurredAt ?: memory.createdAt}] [${CharacterAssociativeRecall.memoryLane(memory).label}] memoryId=${memory.id} ${memory.content}" +
                        if (memory.kind == com.jiacimu.lulu.core.MemoryKind.Emotion) "（当时的情绪印象，不代表此刻状态）" else "",
                    650, payload = memory.content, sources = memory.sourceIds().toSet(),
                    derivedSummary = memory.kind != com.jiacimu.lulu.core.MemoryKind.Emotion))
            }
            lexicon.forEachIndexed { index, entry ->
                if (entry.section == LexiconSection.Concern) {
                    add(MemoryPromptCandidate("lexicon:${entry.id}", LEXICON,
                        "- [旧版关心笔记/仅供历史参考，非当前挂心、非用户现状、非行动指令] ${entry.title}：${entry.content}",
                        360 - index.coerceAtMost(80), payload = entry.content,
                        sources = entry.sourceEventIds.toSet(), derivedSummary = true))
                    return@forEachIndexed
                }
                val subjective = entry.section in setOf(LexiconSection.Diary, LexiconSection.Favorite)
                add(MemoryPromptCandidate("lexicon:${entry.id}", LEXICON,
                    if (entry.section == LexiconSection.Life)
                        "- [真实生活近况/收录于${entry.updatedAt}，不代表每日习惯] ${entry.title} [${entry.status}] entryId=${entry.id}：${entry.content}；只有新旧时间及事实明确时才自然比较变化，不臆测结果"
                    else "- ${entry.section}/${entry.title} [${entry.status}] entryId=${entry.id}：${entry.content}",
                    740 - index.coerceAtMost(80),
                    payload = entry.content, sources = entry.sourceEventIds.toSet(), derivedSummary = !subjective))
            }
        }
        val protected = listOf(coreSection, context.activeTaskContext).filter(String::isNotBlank)
            .joinToString("\n\n").length.let { if (it == 0) 0 else it + 2 }
        val result = MemoryWorkingSetPolicy.select(candidates, budget.workingSetCharacters, protected,
            mapOf(RECENT to budget.recentCharacters, EVIDENCE to budget.evidenceCharacters,
                SUMMARY to budget.memoryCharacters, LEXICON to budget.lexiconCharacters))
        MemoryInspectionStore.recordInjection(context.characterId, context.inspectionId,
            context.coreMemories.map { "core:${it.id}" } + result.selected, result.omitted,
            extraNotes = listOf("统一记忆工作集：弹性 ${result.usedCharacters} 字符；常驻核心/责任 ${result.protectedCharacters} 字符；预算 ${result.budget} 字符。") +
                if (result.protectedCharacters > result.budget) listOf("核心与未结责任超过预算，完整保留；请在记忆页整理，未静默截断。") else emptyList())
        return result
    }
}
