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

data class UnifiedMemoryContext(
    val memories: List<MemoryEntry> = emptyList(),
    val coreMemories: List<MemoryEntry> = emptyList(),
    val sourceEvents: List<SharedTimelineEvent> = emptyList(),
    val recentEvents: List<SharedTimelineEvent> = emptyList(),
    val activeTaskContext: String = "",
    private val evidenceCharacterBudget: Int = 9_000,
    private val recentCharacterBudget: Int = 7_000,
) {
    val coreMemorySection: String
        get() = if (coreMemories.isEmpty()) "" else buildString {
            appendLine("【持续生效的核心记忆｜独立于普通召回名额】")
            appendLine("仅在有关时自然使用，不逐条复述；当前明确纠正优先，临时状态不覆盖长期事实。")
            coreMemories.forEach { appendLine("- ${it.content.trim().replace("\n", " ")}") }
        }.trim()

    val sourceEvidence: String
        get() = renderEventSection(
            "召回记忆对应的原始时间线证据：",
            "事实、措辞和时间以这些原始记录为准；摘要只用于检索。",
            sourceEvents,
            evidenceCharacterBudget,
        )

    val recentTimeline: String
        get() = listOf(
            activeTaskContext,
            renderEventLines(recentEvents, recentCharacterBudget).joinToString("\n"),
        ).filter(String::isNotBlank).joinToString("\n")

    fun compactPromptSection(characterBudget: Int = 4_800): String {
        if (coreMemories.isEmpty() && memories.isEmpty() && sourceEvents.isEmpty() && recentEvents.isEmpty() && activeTaskContext.isBlank()) return ""
        val safeBudget = characterBudget.coerceAtLeast(900)
        val recentBudget = (safeBudget * 0.42).toInt()
        val evidenceBudget = (safeBudget * 0.36).toInt()
        val summaryBudget = (safeBudget - recentBudget - evidenceBudget).coerceAtLeast(120)
        return listOf(
            coreMemorySection,
            activeTaskContext,
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
        recallLimit: Int = 96,
        evidenceLimit: Int = 32,
        evidenceCharacterBudget: Int = 9_000,
        recentCharacterBudget: Int = 7_000,
    ): UnifiedMemoryContext {
        if (characterId.isBlank()) return empty()
        val recentEvents = LuluRepositories.memory.contextTimelineEvents(characterId)
        val recentIds = recentEvents.mapTo(mutableSetOf(), SharedTimelineEvent::id)
        val query = request.retrievalQuery()
        val core = LuluRepositories.memory.snapshot(characterId).filter {
            it.isCoreMemory() && MemoryValidityStore.isActive(it.id) && memoryHasLiveSources(it, characterId)
        }
        val coreIds = core.mapTo(mutableSetOf(), MemoryEntry::id)
        val recalled = RelevantMemoryRecall.recall(characterId, query, recallLimit)
            .filter { memory -> MemoryValidityStore.isActive(memory.id) }
            .filter { memory ->
                val sourceIds = memory.sourceEventIds()
                sourceIds.isEmpty() || sourceIds.all { sourceId ->
                    SharedExperienceTimeline.eventsByIds(characterId, listOf(sourceId)).isNotEmpty()
                }
            }
        val memories = selectMemoryWithinBudget(recalled.filterNot { it.id in coreIds }.filter { memory ->
            val sourceIds = memory.sourceEventIds()
            sourceIds.isEmpty() || sourceIds.any { sourceId -> sourceId !in recentIds }
        })
        val sourceEvents = (RelevantMemoryRecall.sourceEvidenceEvents(
            characterId = characterId,
            query = query,
            memories = memories,
            limit = evidenceLimit,
        ) + RawTimelineMemoryRecall.find(characterId, query, limit = 16))
            .distinctBy(SharedTimelineEvent::id).filterNot { event -> event.id in recentIds }
        val activeTasks = renderActiveCommitmentTasks(characterId)
        MemoryInspectionStore.recordRecall(characterId, query, memories, sourceEvents)
        return UnifiedMemoryContext(
            memories = memories,
            coreMemories = core,
            sourceEvents = sourceEvents,
            recentEvents = recentEvents,
            activeTaskContext = activeTasks,
            evidenceCharacterBudget = evidenceCharacterBudget,
            recentCharacterBudget = recentCharacterBudget,
        )
    }

    suspend fun assemble(
        characterId: String,
        query: String,
        recallLimit: Int = 96,
        evidenceLimit: Int = 32,
        evidenceCharacterBudget: Int = 9_000,
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

private fun renderActiveCommitmentTasks(characterId: String): String {
    val tasks = CommitmentTaskStore.active(characterId)
    return buildString {
        appendLine("【责任连续性规则｜持久化任务优先于随口生成】")
        appendLine("- 如果你准备接受叫醒、提醒、监督或其他未来责任，但用户没有说清多久、几点、日期或必要完成条件，必须先自然追问缺失信息；不能替用户猜一个时间再答应。")
        appendLine("- 已经接受的责任不能因为换页面、聊天变多或普通后台感知而失效；任务完成只能依据程序执行结果和用户明确反馈。没有回复只代表尚未确认。")
        if (tasks.isEmpty()) {
            append("当前没有未完成责任。")
            return@buildString
        }
        appendLine("以下是当前全部未完成责任，直接来自任务 Store，不依赖语义召回或辞海数量：")
        tasks.forEach { task ->
            append("- taskId=${task.id}；revision=${task.revision}；status=${task.status.name}；目标=${task.goal.take(300)}")
            task.dueAt?.let { append("；dueAt=$it") }
            task.timezone?.takeIf(String::isNotBlank)?.let { append("；timezone=$it") }
            task.nextCheckAt?.let { append("；nextCheckAt=$it") }
            if (task.steps.isNotEmpty()) append("；step=${task.currentStep}/${task.steps.size}")
            if (task.attemptCount > 0) append("；attempts=${task.attemptCount}")
            if (task.completionCondition.isNotBlank()) append("；完成条件=${task.completionCondition.take(220)}")
            if (task.lastActionResult.isNotBlank()) append("；最近结果=${task.lastActionResult.take(260)}")
            when (task.status) {
                CommitmentTaskStatus.NeedsClarification -> append("；下一步=自然向用户问清缺失时间/条件，禁止猜测")
                CommitmentTaskStatus.WaitingForFeedback -> append("；下一步=等待明确反馈，不得自行宣称已完成")
                CommitmentTaskStatus.Blocked -> append("；下一步=说明受阻事实或等待可执行条件，不得假装执行成功")
                else -> Unit
            }
            appendLine()
        }
    }.trim()
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
    memories.forEach { memory ->
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

internal fun renderEventLines(events: List<SharedTimelineEvent>, characterBudget: Int): List<String> {
    if (events.isEmpty() || characterBudget <= 0) return emptyList()
    val kept = mutableListOf<String>()
    var remaining = characterBudget
    // Keep newest useful events. A character budget is a hard ceiling, even with a large backlog.
    for (event in events.asReversed()) {
        val prefix = "[${event.occurredAt}] [${event.channel}] ${event.speaker}："
        val room = remaining - prefix.length - if (kept.isEmpty()) 0 else 1
        if (room < 48) break
        val content = event.evidenceContent.trim().replace("\n", " ")
        val limit = minOf(room, 1_200)
        val line = prefix + if (content.length <= limit) content else content.take(limit - 1) + "…"
        kept += line
        remaining -= line.length + if (kept.size == 1) 0 else 1
    }
    return kept.asReversed()
}
