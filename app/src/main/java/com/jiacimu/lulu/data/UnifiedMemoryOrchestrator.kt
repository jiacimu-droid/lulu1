package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry

/**
 * The single memory dispatcher for every companion-life model surface.
 *
 * Raw shared timeline events remain the source of truth. Semantic memories are retrieval pointers:
 * a hit is expanded back to its exact source events, then combined with the recent raw timeline.
 */
data class UnifiedMemoryContext(
    val memories: List<MemoryEntry> = emptyList(),
    val sourceEvidence: String = "",
    val recentTimeline: String = "",
) {
    fun compactPromptSection(characterBudget: Int = 4_800): String {
        if (memories.isEmpty() && sourceEvidence.isBlank() && recentTimeline.isBlank()) return ""
        val safeBudget = characterBudget.coerceAtLeast(600)
        val recentBudget = (safeBudget * 0.38).toInt()
        val evidenceBudget = (safeBudget * 0.38).toInt()
        val summaryBudget = safeBudget - recentBudget - evidenceBudget
        val recent = recentTimeline.take(recentBudget)
        val evidence = sourceEvidence.take(evidenceBudget)
        val summaries = buildString {
            memories.take(8).forEach { memory ->
                appendLine("- ${memory.content.take(420)}")
            }
        }.trim().take(summaryBudget)
        return buildString {
            if (recent.isNotBlank()) {
                appendLine("这个角色最近亲历的原始时间线：")
                appendLine(recent)
            }
            if (evidence.isNotBlank()) {
                appendLine("与当前内容语义相关、从记忆指针回溯出的原始记录：")
                appendLine(evidence)
            }
            if (summaries.isNotBlank()) {
                appendLine("用于关联检索的记忆摘要（事实以原始记录为准）：")
                appendLine(summaries)
            }
        }.trim().take(safeBudget)
    }
}

object UnifiedMemoryOrchestrator {
    fun empty(): UnifiedMemoryContext = UnifiedMemoryContext()

    suspend fun assemble(
        characterId: String,
        query: String,
        recallLimit: Int = 12,
        evidenceLimit: Int = 10,
        evidenceCharacterBudget: Int = 4_200,
        recentLimit: Int = 18,
        recentCharacterBudget: Int = 6_000,
    ): UnifiedMemoryContext {
        if (characterId.isBlank()) return empty()
        val memories = RelevantMemoryRecall.recall(
            characterId = characterId,
            query = query,
            limit = recallLimit,
        )
        return UnifiedMemoryContext(
            memories = memories,
            sourceEvidence = RelevantMemoryRecall.sourceEvidenceForPrompt(
                characterId = characterId,
                query = query,
                memories = memories,
                limit = evidenceLimit,
                characterBudget = evidenceCharacterBudget,
            ),
            recentTimeline = SharedExperienceTimeline.recentContext(
                characterId = characterId,
                limit = recentLimit,
                characterBudget = recentCharacterBudget,
            ),
        )
    }
}
