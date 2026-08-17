package com.jiacimu.lulu.data

import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry

data class UnifiedMemoryRequest(
    val currentInput: String = "",
    val sceneContext: String = "",
    val recentContext: String = "",
    val taskIntent: String = "",
) {
    fun retrievalQuery(): String = buildString {
        if (currentInput.isNotBlank()) appendLine("当前输入：${currentInput.trim()}")
        if (sceneContext.isNotBlank()) appendLine("当前场景：${sceneContext.trim()}")
        if (recentContext.isNotBlank()) appendLine("近期上下文：${recentContext.trim()}")
        if (taskIntent.isNotBlank()) appendLine("任务意图：${taskIntent.trim()}")
    }.trim()

    companion object {
        fun legacy(facts: String, instruction: String): UnifiedMemoryRequest = UnifiedMemoryRequest(
            recentContext = facts,
            taskIntent = instruction,
        )
    }
}

/** Structured events are kept until rendering, avoiding blind substring cuts through records. */
data class UnifiedMemoryContext(
    val memories: List<MemoryEntry> = emptyList(),
    val sourceEvents: List<SharedTimelineEvent> = emptyList(),
    val recentEvents: List<SharedTimelineEvent> = emptyList(),
    private val evidenceCharacterBudget: Int = 4_200,
    private val recentCharacterBudget: Int = 7_000,
) {
    val sourceEvidence: String
        get() = renderEventSection(
            "召回记忆对应的原始时间线证据：",
            "事实、措辞和时间以这些原始记录为准；摘要只用于检索。",
            sourceEvents,
            evidenceCharacterBudget,
        )

    val recentTimeline: String
        get() = renderEventLines(recentEvents, recentCharacterBudget).joinToString("\n")

    fun compactPromptSection(characterBudget: Int = 4_800): String {
        if (memories.isEmpty() && sourceEvents.isEmpty() && recentEvents.isEmpty()) return ""
        val safeBudget = characterBudget.coerceAtLeast(900)
        val recentBudget = (safeBudget * 0.46).toInt()
        val evidenceBudget = (safeBudget * 0.40).toInt()
        val summaryBudget = (safeBudget - recentBudget - evidenceBudget).coerceAtLeast(120)
        return listOf(
            renderEventSection("这个角色最近亲历的原始时间线：", "", recentEvents, recentBudget),
            renderEventSection(
                "与当前内容语义相关、从记忆指针回溯出的原始记录：",
                "已经与近期窗口按事件 ID 去重；事实以原始记录为准。",
                sourceEvents,
                evidenceBudget,
            ),
            renderMemorySummaries(memories, summaryBudget),
        ).filter(String::isNotBlank).joinToString("\n")
    }
}

object UnifiedMemoryOrchestrator {
    fun empty(): UnifiedMemoryContext = UnifiedMemoryContext()

    suspend fun assemble(
        characterId: String,
        request: UnifiedMemoryRequest,
        recallLimit: Int = 12,
        evidenceLimit: Int = 10,
        evidenceCharacterBudget: Int = 4_200,
        recentCharacterBudget: Int = 7_000,
    ): UnifiedMemoryContext {
        if (characterId.isBlank()) return empty()
        val recentEvents = LuluRepositories.memory.contextTimelineEvents(characterId)
        val recentIds = recentEvents.mapTo(mutableSetOf(), SharedTimelineEvent::id)
        val query = request.retrievalQuery()
        val memories = RelevantMemoryRecall.recall(characterId, query, recallLimit)
            .filterNot { memory -> memory.sourceEventIds().any(recentIds::contains) }
        val sourceEvents = RelevantMemoryRecall.sourceEvidenceEvents(
            characterId = characterId,
            query = query,
            memories = memories,
            limit = evidenceLimit,
        ).filterNot { event -> event.id in recentIds }
        return UnifiedMemoryContext(
            memories = memories,
            sourceEvents = sourceEvents,
            recentEvents = recentEvents,
            evidenceCharacterBudget = evidenceCharacterBudget,
            recentCharacterBudget = recentCharacterBudget,
        )
    }

    suspend fun assemble(
        characterId: String,
        query: String,
        recallLimit: Int = 12,
        evidenceLimit: Int = 10,
        evidenceCharacterBudget: Int = 4_200,
        recentCharacterBudget: Int = 7_000,
    ): UnifiedMemoryContext = assemble(
        characterId,
        UnifiedMemoryRequest(currentInput = query),
        recallLimit,
        evidenceLimit,
        evidenceCharacterBudget,
        recentCharacterBudget,
    )
}

private fun MemoryEntry.sourceEventIds(): List<String> = when {
    source.startsWith("timeline-events:") -> source.removePrefix("timeline-events:").split('|')
    source.startsWith("timeline-batch:") -> source.removePrefix("timeline-batch:").split('|')
    else -> emptyList()
}.filter(String::isNotBlank)

private fun renderMemorySummaries(memories: List<MemoryEntry>, characterBudget: Int): String {
    if (memories.isEmpty() || characterBudget < 80) return ""
    val header = "用于关联检索的记忆摘要（事实以原始记录为准）："
    val lines = mutableListOf<String>()
    var used = header.length + 1
    memories.take(8).forEach { memory ->
        val memoryTime = memory.occurredAt ?: memory.createdAt
        val line = "- [$memoryTime] ${memory.content.trim().replace("\n", " ")}"
        if (used + line.length + 1 <= characterBudget) {
            lines += line
            used += line.length + 1
        }
    }
    return if (lines.isEmpty()) "" else (listOf(header) + lines).joinToString("\n")
}

private fun renderEventSection(
    title: String,
    note: String,
    events: List<SharedTimelineEvent>,
    characterBudget: Int,
): String {
    if (events.isEmpty() || characterBudget < title.length + 40) return ""
    val lines = renderEventLines(events, (characterBudget - title.length - note.length - 2).coerceAtLeast(0))
    if (lines.isEmpty()) return ""
    return buildString {
        appendLine(title)
        if (note.isNotBlank()) appendLine(note)
        append(lines.joinToString("\n"))
    }
}

private fun renderEventLines(events: List<SharedTimelineEvent>, characterBudget: Int): List<String> {
    if (events.isEmpty() || characterBudget <= 0) return emptyList()
    val prefixes = events.map { event -> "[${event.occurredAt}] [${event.channel}] ${event.speaker}：" }
    val prefixCost = prefixes.sumOf(String::length) + events.size
    // Budgets are soft here: omitting an older unresolved event is worse than a modest overflow.
    // Share the available content space across every chronological record instead of filling from
    // newest to oldest and silently dropping the beginning of the pending window.
    val effectiveBudget = maxOf(characterBudget, prefixCost + events.size * MIN_EVENT_CONTENT_CHARS)
    val contentBudget = (effectiveBudget - prefixCost).coerceAtLeast(events.size)
    val perEvent = (contentBudget / events.size).coerceAtLeast(MIN_EVENT_CONTENT_CHARS)
    return events.mapIndexed { index, event ->
        val content = event.content.trim().replace("\n", " ")
        prefixes[index] + if (content.length <= perEvent) content else content.take(perEvent) + "…"
    }
}

private const val MIN_EVENT_CONTENT_CHARS = 48
