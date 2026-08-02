package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * Retry-independent safety net migrated from Lulu's affective memory extractor.
 * Explicit preferences, boundaries and corrections are durable facts even when the model-based
 * batch extractor is unavailable. Each user message is checkpointed only after inspection.
 */
object DeterministicMemoryAutomation {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val conversationJobs = mutableMapOf<String, Job>()
    private var prefs: android.content.SharedPreferences? = null
    private var started = false

    @Synchronized
    fun initialize(context: Context) {
        if (started) return
        started = true
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        scope.launch {
            MigratedDomainStores.chat.conversations.collect { conversations ->
                val liveIds = conversations.mapTo(mutableSetOf()) { it.id }
                conversationJobs.keys
                    .filterNot(liveIds::contains)
                    .forEach { id -> conversationJobs.remove(id)?.cancel() }
                conversations.forEach { conversation ->
                    if (conversation.id in conversationJobs) return@forEach
                    conversationJobs[conversation.id] = scope.launch {
                        MigratedDomainStores.chat.messages(conversation.id)
                            .drop(1)
                            .collect { messages ->
                                messages.asSequence()
                                    .filter { it.sender == LuluChatMessage.Sender.User }
                                    .filterNot { wasProcessed(it.id) }
                                    .forEach { message ->
                                        inspectAndSave(conversation.characterId, message)
                                        markProcessed(message.id)
                                    }
                            }
                    }
                }
            }
        }
    }

    private suspend fun inspectAndSave(characterId: String, message: LuluChatMessage) {
        val quote = message.content
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(220)
        if (quote.length < 4) return
        val category = when {
            BOUNDARY_MARKERS.any { quote.contains(it, ignoreCase = true) } -> ExplicitCategory.Boundary
            CORRECTION_MARKERS.any { quote.contains(it, ignoreCase = true) } -> ExplicitCategory.Correction
            PREFERENCE_MARKERS.any { quote.contains(it, ignoreCase = true) } -> ExplicitCategory.Preference
            else -> return
        }
        val content = when (category) {
            ExplicitCategory.Boundary -> "主人明确表达过边界：$quote"
            ExplicitCategory.Correction -> "主人纠正过一件事：$quote"
            ExplicitCategory.Preference -> "主人明确表达过偏好：$quote"
        }
        val identity = normalize(content)
        if (LuluRepositories.memory.snapshot(characterId).any { normalize(it.content) == identity }) return
        LuluRepositories.memory.upsert(
            MemoryEntry(
                id = UUID.randomUUID().toString(),
                characterId = characterId,
                content = content,
                kind = MemoryKind.Fact,
                source = "聊天·明确表达",
                occurredAt = message.createdAt,
                createdAt = Instant.now(),
                strength = when (category) {
                    ExplicitCategory.Preference -> 7
                    ExplicitCategory.Boundary, ExplicitCategory.Correction -> 9
                },
                pinned = false,
                canRecallProactively = true,
            ),
        )
    }

    private fun wasProcessed(messageId: String): Boolean =
        prefs?.getBoolean("processed:$messageId", false) == true

    private fun markProcessed(messageId: String) {
        prefs?.edit()?.putBoolean("processed:$messageId", true)?.apply()
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\p{P}\\p{S}\\s]+"), "")

    private enum class ExplicitCategory { Preference, Boundary, Correction }

    private const val PREFS_NAME = "lulu_deterministic_memory_automation"

    private val PREFERENCE_MARKERS = listOf(
        "我喜欢", "我更喜欢", "我不喜欢", "我讨厌", "我偏好", "我希望", "我想要",
        "最喜欢", "更想", "不要给我", "别给我", "prefer", "i like", "i dislike",
    )
    private val BOUNDARY_MARKERS = listOf(
        "不要这样", "不许", "不能这样", "别再", "不要再", "这是我的底线", "我不能接受",
        "不可以", "禁止", "别叫我", "不要叫我", "stop doing", "do not",
    )
    private val CORRECTION_MARKERS = listOf(
        "你记错了", "不是这样的", "我说的是", "纠正一下", "不是这个意思", "你理解错了",
        "其实是", "应该是", "不是", "that's wrong", "i meant",
    )
}
