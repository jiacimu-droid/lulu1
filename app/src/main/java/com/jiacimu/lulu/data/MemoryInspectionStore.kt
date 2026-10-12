package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

data class MemoryRecallInspection(
    val characterId: String,
    val query: String,
    val memories: List<Pair<String, String>>,
    val evidence: List<Pair<String, String>>,
    val updatedAt: Instant,
    val traceId: String = "",
    val candidateCount: Int = 0,
    val candidates: List<Pair<String, String>> = emptyList(),
    val reasons: Map<String, String> = emptyMap(),
    val omitted: Map<String, String> = emptyMap(),
    val injected: List<String> = emptyList(),
    val notes: List<String> = emptyList(),
    val promptRecorded: Boolean = false,
)

/** Small diagnostic snapshot used by the existing memory debug card; never enters model context. */
internal object MemoryInspectionStore {
    private val recalls = ConcurrentHashMap<String, MemoryRecallInspection>()
    @Volatile private var changeListener: ((String) -> Unit)? = null

    fun setChangeListener(listener: ((String) -> Unit)?) {
        changeListener = listener
    }

    fun recordRecall(
        characterId: String,
        query: String,
        memories: List<MemoryEntry>,
        sourceEvents: List<SharedTimelineEvent>,
        candidates: List<MemoryEntry> = memories,
        reasons: Map<String, String> = emptyMap(),
        omitted: Map<String, String> = emptyMap(),
        notes: List<String> = emptyList(),
    ): String {
        if (characterId.isBlank()) return ""
        val traceId = UUID.randomUUID().toString()
        recalls[characterId] = MemoryRecallInspection(
            characterId = characterId,
            query = query.take(600),
            memories = memories.take(18).map { it.id to it.content.take(500) },
            evidence = sourceEvents.take(18).map { event ->
                event.id to "[${event.channel}] ${event.speaker}：${event.evidenceContent.take(500)}"
            },
            updatedAt = Instant.now(),
            traceId = traceId,
            candidateCount = candidates.size,
            candidates = candidates.take(32).map { it.id to it.content.take(220) },
            reasons = reasons,
            omitted = omitted,
            notes = notes,
        )
        changeListener?.invoke(characterId)
        return traceId
    }

    fun recordInjection(characterId: String, traceId: String, injected: List<String>,
                        omitted: Map<String, String>, truncated: List<String> = emptyList()) {
        if (traceId.isBlank()) return
        recalls.computeIfPresent(characterId) { _, current ->
            if (current.traceId != traceId) current else current.copy(
                injected = injected.distinct(), omitted = current.omitted + omitted,
                notes = current.notes + truncated.distinct().map { "原始内容保留，本次上下文缩略：$it" },
                promptRecorded = true)
        }
        changeListener?.invoke(characterId)
    }

    fun clearCharacter(characterId: String) {
        recalls.remove(characterId)
    }

    fun snapshot(characterId: String): MemoryRecallInspection? = recalls[characterId]

    fun render(characterId: String): String {
        val recall = recalls[characterId] ?: return "最近召回：本次启动后尚无召回记录。"
        return buildString {
            appendLine("最近召回：${recall.updatedAt}")
            appendLine("查询：${recall.query.ifBlank { "（空）" }}")
            appendLine("候选=${recall.candidateCount}；已记录实际注入=${recall.promptRecorded}；实际注入=${recall.injected.size}")
            if (recall.memories.isEmpty()) {
                appendLine("结果：0 条（低相关时允许不召回）")
            } else {
                appendLine("结果：${recall.memories.size} 条")
                recall.memories.forEach { (id, content) -> appendLine("- memoryId=$id：$content") }
            }
            if (recall.evidence.isNotEmpty()) {
                appendLine("对应原始证据：")
                recall.evidence.forEach { (id, content) -> appendLine("- eventId=$id：$content") }
            }
            if (recall.injected.isNotEmpty()) appendLine("实际注入ID：${recall.injected.joinToString("、")}")
            if (recall.omitted.isNotEmpty()) {
                appendLine("本次未进入上下文（不是删除记忆）：")
                recall.omitted.entries.take(24).forEach { (id, why) -> appendLine("- $id：$why") }
            }
            if (recall.reasons.isNotEmpty()) {
                appendLine("召回依据：")
                recall.reasons.entries.take(18).forEach { (id, why) -> appendLine("- $id：$why") }
            }
            recall.notes.distinct().forEach { appendLine("检索状态：$it") }
        }.trim()
    }
}
