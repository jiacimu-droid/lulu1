package com.jiacimu.lulu.data

import com.jiacimu.lulu.core.DurationSummary
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconRepository
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.MemoryRepository
import com.jiacimu.lulu.core.PerformanceRepository
import com.jiacimu.lulu.core.TokenUsage
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * Lulu1 的首批独立数据实现。
 *
 * 这些仓库用于连接重新设计后的 Compose 页面，并为后续接入数据库迁移层提供稳定接口。
 * 它们不依赖旧项目的 Activity、Composable、路由或页面状态。
 */
class InMemoryMemoryRepository : MemoryRepository {
    private val memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    private val policies = MutableStateFlow<Map<String, MemoryPolicy>>(emptyMap())

    override fun observeMemories(characterId: String): Flow<List<MemoryEntry>> =
        memories.map { entries ->
            entries
                .filter { it.characterId == characterId }
                .sortedWith(compareByDescending<MemoryEntry> { it.pinned }.thenByDescending { it.createdAt })
        }

    override fun observePolicy(characterId: String): Flow<MemoryPolicy> =
        policies.map { it[characterId] ?: MemoryPolicy() }

    override suspend fun updatePolicy(characterId: String, policy: MemoryPolicy) {
        require(policy.excludedRecentMessages >= 0) { "最近消息排除数量不能为负数" }
        require(policy.readableThreshold >= 0) { "总结阈值不能为负数" }
        policies.update { it + (characterId to policy) }
    }

    override suspend fun summarizeNow(characterId: String) {
        // 真正的模型总结将在迁入记忆服务后接入。这里保留稳定调用入口。
    }

    suspend fun replaceAll(entries: List<MemoryEntry>) {
        memories.value = entries
    }

    suspend fun upsert(entry: MemoryEntry) {
        memories.update { current -> current.filterNot { it.id == entry.id } + entry }
    }

    suspend fun delete(id: String) {
        memories.update { current -> current.filterNot { it.id == id } }
    }
}

class InMemoryLexiconRepository : LexiconRepository {
    private val entries = MutableStateFlow<List<LexiconEntry>>(emptyList())

    override fun observeEntries(
        characterId: String,
        section: LexiconSection,
    ): Flow<List<LexiconEntry>> = entries.map { current ->
        current
            .filter { it.characterId == characterId && it.section == section }
            .sortedByDescending { it.updatedAt }
    }

    override suspend fun save(entry: LexiconEntry) {
        require(entry.title.isNotBlank()) { "辞海条目标题不能为空" }
        entries.update { current -> current.filterNot { it.id == entry.id } + entry }
    }

    override suspend fun delete(id: String) {
        entries.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun replaceAll(newEntries: List<LexiconEntry>) {
        entries.value = newEntries
    }
}

class InMemoryWorldBookRepository : WorldBookRepository {
    private val entries = MutableStateFlow<List<WorldBookEntry>>(emptyList())

    override fun observeWorldBooks(): Flow<List<WorldBookEntry>> = entries

    override suspend fun save(entry: WorldBookEntry) {
        require(entry.title.isNotBlank()) { "世界书标题不能为空" }
        entries.update { current -> current.filterNot { it.id == entry.id } + entry }
    }

    override suspend fun delete(id: String) {
        entries.update { current -> current.filterNot { it.id == id } }
    }

    suspend fun replaceAll(newEntries: List<WorldBookEntry>) {
        entries.value = newEntries
    }

    fun observeForCharacter(characterId: String): Flow<List<WorldBookEntry>> = entries.map { current ->
        current.filter { entry ->
            entry.characterOverrides[characterId] ?: entry.globalEnabled
        }
    }
}

class InMemoryPerformanceRepository : PerformanceRepository {
    private val errors = MutableStateFlow<List<String>>(emptyList())
    private val tokenUsage = MutableStateFlow(TokenUsage(input = 0, output = 0))
    private val durations = MutableStateFlow(DurationSummary(studyMinutes = 0, chatMinutes = 0, callMinutes = 0))
    private val cacheBytes = MutableStateFlow(0L)

    override fun observeErrors(): Flow<List<String>> = errors
    override fun observeTokenUsage(): Flow<TokenUsage> = tokenUsage
    override fun observeDurations(): Flow<DurationSummary> = durations

    fun observeOverview(): Flow<PerformanceOverview> = combine(
        errors,
        tokenUsage,
        durations,
        cacheBytes,
    ) { currentErrors, usage, currentDurations, bytes ->
        PerformanceOverview(
            errorCount = currentErrors.size,
            tokenUsage = usage,
            durations = currentDurations,
            cacheBytes = bytes,
        )
    }

    override suspend fun clearErrors() {
        errors.value = emptyList()
    }

    override suspend fun clearCache() {
        cacheBytes.value = 0
    }

    suspend fun recordError(message: String) {
        if (message.isBlank()) return
        errors.update { current -> (listOf(message) + current).take(MAX_ERRORS) }
    }

    suspend fun addTokenUsage(input: Int, output: Int, cached: Int = 0) {
        tokenUsage.update { current ->
            current.copy(
                input = current.input + input.coerceAtLeast(0),
                output = current.output + output.coerceAtLeast(0),
                cached = current.cached + cached.coerceAtLeast(0),
            )
        }
    }

    suspend fun updateTokenUsage(usage: TokenUsage) {
        tokenUsage.value = usage.copy(
            input = usage.input.coerceAtLeast(0),
            output = usage.output.coerceAtLeast(0),
            cached = usage.cached.coerceAtLeast(0),
        )
    }

    suspend fun updateDurations(summary: DurationSummary) {
        durations.value = summary.copy(
            studyMinutes = summary.studyMinutes.coerceAtLeast(0),
            chatMinutes = summary.chatMinutes.coerceAtLeast(0),
            callMinutes = summary.callMinutes.coerceAtLeast(0),
        )
    }

    suspend fun updateCacheBytes(bytes: Long) {
        cacheBytes.value = bytes.coerceAtLeast(0)
    }

    private companion object {
        const val MAX_ERRORS = 200
    }
}

data class PerformanceOverview(
    val errorCount: Int,
    val tokenUsage: TokenUsage,
    val durations: DurationSummary,
    val cacheBytes: Long,
)
