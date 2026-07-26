package com.jiacimu.lulu.core

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Lulu1 的页面只依赖这些独立契约。
 * 从旧仓库迁移业务逻辑时，实现接口即可，不复制旧页面或旧导航。
 */

data class CharacterSummary(
    val id: String,
    val name: String,
    val avatarUri: String? = null,
    val status: String? = null
)

data class ConversationSummary(
    val id: String,
    val character: CharacterSummary,
    val latestMessage: String,
    val latestAt: Instant,
    val unreadCount: Int = 0
)

data class ChatMessage(
    val id: String,
    val conversationId: String,
    val sender: Sender,
    val content: String,
    val createdAt: Instant,
    val state: MessageState = MessageState.Complete
) {
    enum class Sender { User, Character, System }
    enum class MessageState { Sending, Streaming, Complete, Failed }
}

interface ConversationRepository {
    fun observeConversations(): Flow<List<ConversationSummary>>
    fun observeMessages(conversationId: String): Flow<List<ChatMessage>>
    suspend fun sendMessage(conversationId: String, content: String)
}

enum class MemoryKind { Fact, Emotion, Timeline }

data class MemoryEntry(
    val id: String,
    val characterId: String,
    val content: String,
    val kind: MemoryKind,
    val source: String,
    val occurredAt: Instant?,
    val createdAt: Instant,
    val strength: Int,
    val pinned: Boolean,
    val canRecallProactively: Boolean
)

data class MemoryPolicy(
    val excludedRecentMessages: Int = 10,
    val readableThreshold: Int = 20,
    val autoSummarize: Boolean = true
)

interface MemoryRepository {
    fun observeMemories(characterId: String): Flow<List<MemoryEntry>>
    fun observePolicy(characterId: String): Flow<MemoryPolicy>
    suspend fun updatePolicy(characterId: String, policy: MemoryPolicy)
    suspend fun summarizeNow(characterId: String)
}

enum class LexiconSection { Life, Concern, Promise, Diary }
enum class PromiseKind { Promise, Responsibility, Reminder, LongTermSupervision }

data class LexiconEntry(
    val id: String,
    val characterId: String,
    val section: LexiconSection,
    val title: String,
    val content: String,
    val promiseKind: PromiseKind? = null,
    val createdAt: Instant,
    val updatedAt: Instant
)

interface LexiconRepository {
    fun observeEntries(characterId: String, section: LexiconSection): Flow<List<LexiconEntry>>
    suspend fun save(entry: LexiconEntry)
    suspend fun delete(id: String)
}

data class WorldBookEntry(
    val id: String,
    val title: String,
    val content: String,
    val globalEnabled: Boolean,
    val characterOverrides: Map<String, Boolean>
)

interface WorldBookRepository {
    fun observeWorldBooks(): Flow<List<WorldBookEntry>>
    suspend fun save(entry: WorldBookEntry)
    suspend fun delete(id: String)
}

data class TokenUsage(
    val input: Long,
    val output: Long,
    val cached: Long = 0,
    val model: String? = null
)

data class DurationSummary(
    val studyMinutes: Long,
    val chatMinutes: Long,
    val callMinutes: Long
)

interface PerformanceRepository {
    fun observeErrors(): Flow<List<String>>
    fun observeTokenUsage(): Flow<TokenUsage>
    fun observeDurations(): Flow<DurationSummary>
    suspend fun clearErrors()
    suspend fun clearCache()
}
