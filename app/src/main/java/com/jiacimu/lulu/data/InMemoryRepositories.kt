package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconRepository
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.MemoryRepository
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

class InMemoryMemoryRepository : MemoryRepository {
    private val memories = MutableStateFlow<List<MemoryEntry>>(emptyList())
    private val policies = MutableStateFlow<Map<String, MemoryPolicy>>(emptyMap())

    override fun observeMemories(characterId: String): Flow<List<MemoryEntry>> =
        memories.map { entries -> entries.forCharacter(characterId) }

    override fun observePolicy(characterId: String): Flow<MemoryPolicy> =
        policies.map { values -> values[characterId] ?: MemoryPolicy() }

    override suspend fun updatePolicy(characterId: String, policy: MemoryPolicy) {
        require(policy.excludedRecentMessages >= 0) { "最近消息排除数量不能为负数" }
        require(policy.readableThreshold >= 0) { "总结阈值不能为负数" }
        policies.update { values -> values + (characterId to policy) }
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
        filter { entry -> entry.characterId == characterId }
            .sortedWith(compareByDescending<MemoryEntry> { it.pinned }.thenByDescending { it.createdAt })
}

/** Retains the old class name while providing persistent local storage. */
class InMemoryLexiconRepository : LexiconRepository {
    private val entries = MutableStateFlow<List<LexiconEntry>>(emptyList())
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            entries.value = decode(prefs?.getString(KEY_ENTRIES, null))
        }
    }

    override fun observeEntries(
        characterId: String,
        section: LexiconSection,
    ): Flow<List<LexiconEntry>> = entries.map { current ->
        current
            .filter { entry -> entry.characterId == characterId && entry.section == section }
            .sortedByDescending { entry -> entry.updatedAt }
    }

    override suspend fun save(entry: LexiconEntry) {
        require(entry.title.isNotBlank()) { "辞海条目标题不能为空" }
        require(entry.content.isNotBlank()) { "辞海条目内容不能为空" }
        val normalized = if (entry.section == LexiconSection.Promise) {
            entry.copy(promiseKind = entry.promiseKind ?: PromiseKind.Promise)
        } else {
            entry.copy(promiseKind = null)
        }
        mutate { current ->
            val index = current.indexOfFirst { item -> item.id == normalized.id }
            if (index < 0) current + normalized else current.toMutableList().apply { set(index, normalized) }
        }
    }

    override suspend fun delete(id: String) {
        val deleted = entries.value.firstOrNull { entry -> entry.id == id }
        mutate { current -> current.filterNot { entry -> entry.id == id } }
        when (deleted?.section) {
            LexiconSection.Diary -> SharedExperienceTimeline.deleteEvent("lexicon-diary-$id")
            LexiconSection.Favorite -> SharedExperienceTimeline.deleteEvent("lexicon-favorite-$id")
            else -> Unit
        }
    }

    fun snapshot(characterId: String): List<LexiconEntry> = entries.value
        .filter { entry -> entry.characterId == characterId }
        .sortedByDescending { entry -> entry.updatedAt }

    suspend fun replaceAll(newEntries: List<LexiconEntry>) {
        mutate { newEntries }
    }

    private fun mutate(transform: (List<LexiconEntry>) -> List<LexiconEntry>) {
        synchronized(lock) {
            val next = transform(entries.value)
            entries.value = next
            prefs?.edit()?.putString(KEY_ENTRIES, encode(next).toString())?.apply()
        }
    }

    private fun encode(values: List<LexiconEntry>): JSONArray = JSONArray().apply {
        values.forEach { entry ->
            put(
                JSONObject()
                    .put("id", entry.id)
                    .put("characterId", entry.characterId)
                    .put("section", entry.section.name)
                    .put("title", entry.title)
                    .put("content", entry.content)
                    .put("promiseKind", entry.promiseKind?.name ?: JSONObject.NULL)
                    .put("createdAt", entry.createdAt.toString())
                    .put("updatedAt", entry.updatedAt.toString()),
            )
        }
    }

    private fun decode(raw: String?): List<LexiconEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val section = runCatching { LexiconSection.valueOf(item.optString("section")) }.getOrNull()
                        ?: continue
                    val title = item.optString("title").trim()
                    val content = item.optString("content").trim()
                    if (title.isBlank() || content.isBlank()) continue
                    val createdAt = item.optString("createdAt").toInstantOrNow()
                    val promiseKind = item.optString("promiseKind")
                        .takeIf { value -> value.isNotBlank() }
                        ?.let { value -> runCatching { PromiseKind.valueOf(value) }.getOrNull() }
                    add(
                        LexiconEntry(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            characterId = item.optString("characterId").ifBlank { "lulu" },
                            section = section,
                            title = title,
                            content = content,
                            promiseKind = if (section == LexiconSection.Promise) {
                                promiseKind ?: PromiseKind.Promise
                            } else {
                                null
                            },
                            createdAt = createdAt,
                            updatedAt = item.optString("updatedAt").toInstantOrNow(createdAt),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private companion object {
        const val PREFS_NAME = "lulu_lexicon"
        const val KEY_ENTRIES = "entries_v1"
    }
}

/**
 * Retains the old class name while providing persistent local storage.
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
            val index = current.indexOfFirst { item -> item.id == entry.id }
            if (index < 0) current + entry else current.toMutableList().apply { set(index, entry) }
        }
    }

    override suspend fun delete(id: String) {
        mutate { current -> current.filterNot { entry -> entry.id == id } }
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
            val from = current.indexOfFirst { entry -> entry.id == id }
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

private fun String.toInstantOrNow(fallback: Instant = Instant.now()): Instant =
    runCatching { Instant.parse(this) }.getOrDefault(fallback)
