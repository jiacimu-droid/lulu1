package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.MemoryEntry
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

data class MemoryRecallInspection(
    val characterId: String,
    val query: String,
    val memories: List<Pair<String, String>>,
    val evidence: List<Pair<String, String>>,
    val updatedAt: Instant,
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
    ) {
        if (characterId.isBlank()) return
        recalls[characterId] = MemoryRecallInspection(
            characterId = characterId,
            query = query.take(600),
            memories = memories.take(18).map { it.id to it.content.take(500) },
            evidence = sourceEvents.take(18).map { event ->
                event.id to "[${event.channel}] ${event.speaker}：${event.evidenceContent.take(500)}"
            },
            updatedAt = Instant.now(),
        )
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
        }.trim()
    }
}
