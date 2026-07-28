package com.jiacimu.lulu.data

import android.content.Context
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
import org.json.JSONArray
import org.json.JSONObject

class InMemoryMemoryRepository : MemoryRepository {
    private val memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    private val policies = MutableStateFlow<Map<String, MemoryPolicy>>(emptyMap())

    override fun observeMemories(characterId: String): Flow<List<MemoryEntry>> =
        memories.map { entries -> entries.forCharacter(characterId) }

    override fun observePolicy(characterId: String): Flow<MemoryPolicy> =
        policies.map { it[characterId] ?: MemoryPolicy() }

    override suspend fun updatePolicy(characterId: String, policy: MemoryPolicy) {
        require(policy.excludedRecentMessages >= 0) { "最近消息排除数量不能为负数" }
        require(policy.readableThreshold >= 0) { "总结阈值不能为负数" }
        policies.update { it + (characterId to policy) }
    }

    override suspend fun summarizeNow(characterId: String) = Unit

    fun snapshot(characterId: String): List<MemoryEntry> = memories.value.forCharacter(characterId)

    suspend fun replaceAll(entries: List<MemoryEntry>) {
        memories.value = entries
    }

    suspend fun upsert(entry: MemoryEntry) {
        memories.update { current -> current.filterNot { it.id == entry.id } + entry }
    }

    suspend fun delete(id: String) {
        memories.update { current -> current.filterNot { it.id == id } }
    }

    private fun List<MemoryEntry>.forCharacter(characterId: String): List<MemoryEntry> =
        filter { it.characterId == characterId }
            .sortedWith(compareByDescending<MemoryEntry> { it.pinned }.thenByDescending { it.createdAt })
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

    fun snapshot(characterId: String): List<LexiconEntry> = entries.value
        .filter { it.characterId == characterId }
        .sortedByDescending { it.updatedAt }

    suspend fun replaceAll(newEntries: List<LexiconEntry>) {
        entries.value = newEntries
    }
}

/**
 * The name is retained to avoid breaking callers, but this repository is now persistent.
 * Array order is the canonical world-book injection order, matching the source project.
 */
class InMemoryWorldBookRepository : WorldBookRepository {
    private val entries = MutableStateFlow<List<WorldBookEntry>>(emptyList())
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            entries.value = decode(prefs?.getString(KEY_ENTRIES, null))
        }
    }

    override fun observeWorldBooks(): Flow<List<WorldBookEntry>> = entries

    override suspend fun save(entry: WorldBookEntry) {
        require(entry.title.isNotBlank()) { "世界书标题不能为空" }
        require(entry.content.isNotBlank()) { "世界设定不能为空" }
        mutate { current ->
            val index = current.indexOfFirst { it.id == entry.id }
            if (index < 0) current + entry else current.toMutableList().apply { set(index, entry) }
        }
    }

    override suspend fun delete(id: String) {
        mutate { current -> current.filterNot { it.id == id } }
    }

    suspend fun setGlobalEnabled(id: String, enabled: Boolean) {
        mutate { current ->
            current.map { entry -> if (entry.id == id) entry.copy(globalEnabled = enabled) else entry }
        }
    }

    suspend fun setCharacterOverride(id: String, characterId: String, enabled: Boolean?) {
        require(characterId.isNotBlank()) { "角色不能为空" }
        mutate { current ->
            current.map { entry ->
                if (entry.id != id) return@map entry
                val overrides = entry.characterOverrides.toMutableMap()
                if (enabled == null) overrides.remove(characterId) else overrides[characterId] = enabled
                entry.copy(characterOverrides = overrides)
            }
        }
    }

    suspend fun move(id: String, direction: Int) {
        if (direction == 0) return
        mutate { current ->
            val from = current.indexOfFirst { it.id == id }
            if (from < 0) return@mutate current
            val to = (from + direction).coerceIn(current.indices)
            if (to == from) return@mutate current
            current.toMutableList().apply {
                val item = removeAt(from)
                add(to, item)
            }
        }
    }

    fun snapshot(): List<WorldBookEntry> = entries.value

    suspend fun replaceAll(newEntries: List<WorldBookEntry>) {
        mutate { newEntries }
    }

    fun isEnabledForCharacter(entry: WorldBookEntry, characterId: String): Boolean =
        entry.characterOverrides[characterId] ?: entry.globalEnabled

    fun observeForCharacter(characterId: String): Flow<List<WorldBookEntry>> = entries.map { current ->
        current.filter { entry -> isEnabledForCharacter(entry, characterId) }
    }

    private fun mutate(transform: (List<WorldBookEntry>) -> List<WorldBookEntry>) {
        synchronized(lock) {
            val next = transform(entries.value)
            entries.value = next
            prefs?.edit()?.putString(KEY_ENTRIES, encode(next).toString())?.apply()
        }
    }

    private fun encode(values: List<WorldBookEntry>): JSONArray = JSONArray().apply {
        values.forEach { entry ->
            put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("content", entry.content)
                    .put("globalEnabled", entry.globalEnabled)
                    .put(
                        "characterOverrides",
                        JSONObject().apply {
                            entry.characterOverrides.forEach { (characterId, enabled) -> put(characterId, enabled) }
                        },
                    ),
            )
        }
    }

    private fun decode(raw: String?): List<WorldBookEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val title = item.optString("title").trim()
                    val content = item.optString("content").trim()
                    if (id.isBlank() || title.isBlank() || content.isBlank()) continue
                    val overridesJson = item.optJSONObject("characterOverrides") ?: JSONObject()
                    val overrides = buildMap {
                        val keys = overridesJson.keys()
                        while (keys.hasNext()) {
                            val key = keys.next()
                            put(key, overridesJson.optBoolean(key))
                        }
                    }
                    add(
                        WorldBookEntry(
                            id = id,
                            title = title,
                            content = content,
                            globalEnabled = item.optBoolean("globalEnabled", false),
                            characterOverrides = overrides,
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "lulu_world_books"
        const val KEY_ENTRIES = "entries_v1"
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

    fun updateDurations(summary: DurationSummary) {
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
