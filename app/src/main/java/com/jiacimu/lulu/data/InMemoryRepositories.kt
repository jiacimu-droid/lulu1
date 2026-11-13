package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.core.LexiconEntry
import com.jiacimu.lulu.core.LexiconRepository
import com.jiacimu.lulu.core.LexiconStatus
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.MemoryRepository
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.core.WorldBookEntry
import com.jiacimu.lulu.core.WorldBookPromptPosition
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
        require(entry.title.isNotBlank()) { "生活记录标题不能为空" }
        require(entry.content.isNotBlank()) { "生活记录内容不能为空" }
        val normalized = if (entry.section == LexiconSection.Promise) {
            entry.copy(promiseKind = entry.promiseKind ?: PromiseKind.Promise)
        } else {
            entry.copy(promiseKind = null)
        }
        synchronized(SharedExperienceTimeline) {
            if (!lexiconHasLiveSources(normalized)) return
            mutate { current ->
                if (current.any { it.id == normalized.id && it.characterId != normalized.characterId }) return@mutate current
                val index = current.indexOfFirst { item -> item.id == normalized.id }
                if (index < 0) current + normalized else current.toMutableList().apply { set(index, normalized) }
            }
        }
    }

    /** Optimistic update: a user edit/delete while a model runs always wins. */
    internal fun saveDerived(entry: LexiconEntry, expected: LexiconEntry?): Boolean = synchronized(SharedExperienceTimeline) {
        if (!lexiconHasLiveSources(entry)) return@synchronized false
        var saved = false
        mutate { current ->
            if (current.firstOrNull { it.id == entry.id } != expected) return@mutate current
            saved = true
            current.filterNot { it.id == entry.id } + entry
        }
        saved
    }

    override suspend fun delete(id: String) {
        val deleted = entries.value.firstOrNull { entry -> entry.id == id }
        // A deleted promise cannot leave its executable timer secretly running.
        if (deleted?.section == LexiconSection.Promise) {
            CommitmentTaskStore.snapshot(deleted.characterId)
                .filter { it.lexiconEntryId == id }
                .forEach { CommitmentTaskStore.remove(it.id) }
        }
        mutate { current -> current.filterNot { entry -> entry.id == id } }
        when (deleted?.section) {
            LexiconSection.Diary -> SharedExperienceTimeline.deleteEvent("lexicon-diary-$id")
            // Keep the legacy fixed ID and all newer, one-action-per-receipt IDs
            // in the SAME owner's lifetime. Never delete another person's event.
            LexiconSection.Favorite -> SharedExperienceTimeline.deleteEventsByIdPrefix(
                deleted.characterId, "lexicon-favorite-$id")
            else -> Unit
        }
    }

    /**
     * Active commitment-backed promise entries are always placed before ordinary recent entries.
     * Callers may still apply a display/token limit, but an old live responsibility can no longer
     * be displaced merely because many newer diary/life/concern items were written afterwards.
     */
    fun snapshot(characterId: String): List<LexiconEntry> {
        val activeLexiconIds = runCatching {
            CommitmentTaskStore.active(characterId).mapNotNullTo(mutableSetOf(), CommitmentTask::lexiconEntryId)
        }.getOrDefault(emptySet())
        return entries.value
            .filter { entry -> entry.characterId == characterId }
            .sortedWith(
                compareByDescending<LexiconEntry> { it.id in activeLexiconIds }
                    .thenByDescending { it.updatedAt },
            )
    }

    fun invalidateSource(eventId: String) {
        mutate { current -> current.filterNot { eventId in it.sourceEventIds } }
    }

    suspend fun replaceAll(newEntries: List<LexiconEntry>) {
        mutate { newEntries }
    }

    private fun mutate(transform: (List<LexiconEntry>) -> List<LexiconEntry>) {
        synchronized(lock) {
            val next = transform(entries.value)
            prefs?.let { check(it.edit().putString(KEY_ENTRIES, encode(next).toString()).commit()) { "记录保存失败" } }
            entries.value = next
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
                    .put("status", entry.status.name)
                    .put("sourceEventIds", JSONArray(entry.sourceEventIds))
                    .put("sourceRevisions", JSONObject(entry.sourceRevisions))
                    .put("lastFollowUpAt", entry.lastFollowUpAt?.toString() ?: JSONObject.NULL)
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
                            sourceRevisions = item.optJSONObject("sourceRevisions")?.let { revisions ->
                                revisions.keys().asSequence().associateWith { revisions.optLong(it) }
                            }.orEmpty(),
                            title = title,
                            content = content,
                            promiseKind = if (section == LexiconSection.Promise) {
                                promiseKind ?: PromiseKind.Promise
                            } else {
                                null
                            },
                            createdAt = createdAt,
                            updatedAt = item.optString("updatedAt").toInstantOrNow(createdAt),
                            status = runCatching { LexiconStatus.valueOf(item.optString("status")) }.getOrDefault(LexiconStatus.Active),
                            sourceEventIds = item.optJSONArray("sourceEventIds")?.let { array -> (0 until array.length()).map { array.optString(it) }.filter(String::isNotBlank) }.orEmpty(),
                            lastFollowUpAt = runCatching { Instant.parse(item.optString("lastFollowUpAt")) }.getOrNull(),
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

data class WorldBookImportResult(val imported: Int, val duplicates: Int)

/** Exact persisted entries and their pre-delete positions for a safe, one-tap undo. */
data class WorldBookDeletedBatch(val entries: List<WorldBookEntry>, val originalIds: List<String>)


/** Search title, body and folder names without affecting original prompt ordering. */
fun groupWorldBookEntries(
    books: List<WorldBookEntry>,
    folders: List<WorldBookFolder>,
    query: String,
): List<Triple<String?, String, List<WorldBookEntry>>> {
    val term = query.trim()
    val validFolderIds = folders.mapTo(mutableSetOf()) { it.id }
    return (listOf<String?>(null) + folders.map { it.id }).mapNotNull { folderId ->
        val name = folders.firstOrNull { it.id == folderId }?.name ?: "未分类"
        val selected = books.filter { book ->
            val actualFolder = book.folderId?.takeIf { it in validFolderIds }
            actualFolder == folderId && (term.isBlank() ||
                name.contains(term, ignoreCase = true) ||
                book.title.contains(term, ignoreCase = true) ||
                book.content.contains(term, ignoreCase = true))
        }
        if (selected.isEmpty() && (folderId == null && term.isBlank() ||
                term.isNotBlank() && !name.contains(term, ignoreCase = true))) null
        else Triple(folderId, name, selected)
    }
}

/** Folder metadata is organizational only; entries remain in their original prompt order. */
data class WorldBookFolder(val id: String, val name: String)

class InMemoryWorldBookRepository : WorldBookRepository {
    private val entries = MutableStateFlow<List<WorldBookEntry>>(emptyList())
    private val folders = MutableStateFlow<List<WorldBookFolder>>(emptyList())
    private var prefs: android.content.SharedPreferences? = null
    private val lock = Any()

    fun initialize(context: Context) {
        synchronized(lock) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            folders.value = decodeFolders(prefs?.getString(KEY_FOLDERS, null))
            val stored = decode(prefs?.getString(KEY_ENTRIES, null))
            val storage = checkNotNull(prefs)
            if (!storage.getBoolean(KEY_HUMAN_CHAT_SEEDED, false)) {
                // Only seed once. An owner-edited or removed entry is never restored.
                val initial = if (stored.any {
                    it.id == HUMAN_CHAT_BOOK_ID || it.title == HUMAN_CHAT_BOOK_TITLE
                }) stored else stored + WorldBookEntry(
                    id = HUMAN_CHAT_BOOK_ID,
                    title = HUMAN_CHAT_BOOK_TITLE,
                    content = HUMAN_CHAT_BOOK_CONTENT.trimIndent(),
                    globalEnabled = true,
                    characterOverrides = emptyMap(),
                    promptPosition = WorldBookPromptPosition.BOTTOM,
                )
                val saved = storage.edit().putString(KEY_ENTRIES, encode(initial).toString())
                    .putBoolean(KEY_HUMAN_CHAT_SEEDED, true)
                    .putBoolean(KEY_HUMAN_CHAT_REVISED_V2, true)
                    .putBoolean(KEY_HUMAN_CHAT_REVISED_V3, true)
                    .putBoolean(KEY_HUMAN_CHAT_REVISED_V4, true).commit()
                entries.value = if (saved) initial else stored
            } else if (!storage.getBoolean(KEY_HUMAN_CHAT_REVISED_V4, false)) {
                // Prior v1-v3 bundled bodies are exact migration fixtures.
                // Only exact known text with the original ID AND title can upgrade;
                // never change the owner's body, title, scopes, folder or position.
                val stockBodies = setOf(
                    HUMAN_CHAT_BOOK_V1_CONTENT.trimIndent(),
                    HUMAN_CHAT_BOOK_V2_CONTENT.trimIndent(),
                    HUMAN_CHAT_BOOK_V3_CONTENT.trimIndent(),
                )
                val revised = stored.map { entry ->
                    if (entry.id == HUMAN_CHAT_BOOK_ID &&
                        entry.title == HUMAN_CHAT_BOOK_TITLE &&
                        entry.content in stockBodies
                    ) entry.copy(content = HUMAN_CHAT_BOOK_CONTENT.trimIndent(),
                        updatedAt = Instant.now())
                    else entry
                }
                val editor = storage.edit().putBoolean(KEY_HUMAN_CHAT_REVISED_V2, true)
                    .putBoolean(KEY_HUMAN_CHAT_REVISED_V3, true)
                    .putBoolean(KEY_HUMAN_CHAT_REVISED_V4, true)
                if (revised != stored) editor.putString(KEY_ENTRIES, encode(revised).toString())
                entries.value = if (editor.commit()) revised else stored
            } else entries.value = stored
        }
    }

    override fun observeWorldBooks(): Flow<List<WorldBookEntry>> = entries
    fun observeFolders(): Flow<List<WorldBookFolder>> = folders

    suspend fun saveFolder(name: String, id: String? = null): String {
        val clean = name.trim()
        require(clean.isNotBlank() && clean.length <= 60) { "分类名称须为 1～60 个字" }
        synchronized(lock) {
            require(folders.value.none { it.name.equals(clean, ignoreCase = true) && it.id != id }) { "分类已存在" }
            val nextId = id ?: UUID.randomUUID().toString()
            val current = folders.value
            require(id == null || current.any { it.id == id }) { "分类不存在" }
            val next = if (id == null) current + WorldBookFolder(nextId, clean)
                       else current.map { if (it.id == id) it.copy(name = clean) else it }
            prefs?.let { check(it.edit().putString(KEY_FOLDERS, encodeFolders(next).toString()).commit()) { "分类保存失败" } }
            folders.value = next
            return nextId
        }
    }

    /** Removing a folder never removes its entries, switches or relative order. */
    suspend fun deleteFolder(id: String) {
        synchronized(lock) {
            if (folders.value.none { it.id == id }) return
            val nextFolders = folders.value.filterNot { it.id == id }
            val nextEntries = entries.value.map { if (it.folderId == id) it.copy(folderId = null) else it }
            prefs?.let {
                check(it.edit().putString(KEY_FOLDERS, encodeFolders(nextFolders).toString())
                    .putString(KEY_ENTRIES, encode(nextEntries).toString()).commit()) { "分类删除失败" }
            }
            entries.value = nextEntries
            folders.value = nextFolders
        }
    }

    suspend fun setEntryFolder(id: String, folderId: String?) = moveEntriesToFolder(setOf(id), folderId)

    /** A bulk move is one persisted transaction, not hundreds of per-entry commits. */
    suspend fun moveEntriesToFolder(ids: Set<String>, folderId: String?) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            require(folderId == null || folders.value.any { it.id == folderId }) { "分类不存在" }
            mutate { current ->
                current.map { if (it.id in ids && it.folderId != folderId)
                    it.copy(folderId = folderId, updatedAt = Instant.now()) else it
                }
            }
        }
    }

    override suspend fun save(entry: WorldBookEntry) {
        require(entry.title.isNotBlank()) { "世界书标题不能为空" }
        require(entry.content.isNotBlank()) { "世界书内容不能为空" }
        synchronized(lock) {
            require(entry.folderId == null || folders.value.any { it.id == entry.folderId }) { "分类不存在" }
        }
        mutate { current ->
            val index = current.indexOfFirst { item -> item.id == entry.id }
            if (index < 0) {
                current + entry.copy(createdAt = entry.createdAt, updatedAt = Instant.now())
            } else {
                val existing = current[index]
                current.toMutableList().apply {
                    set(index, entry.copy(createdAt = existing.createdAt, updatedAt = Instant.now()))
                }
            }
        }
    }

    override suspend fun delete(id: String) {
        deleteEntries(setOf(id))
    }

    /** Delete a selection in one durable write. Returns only entries actually removed. */
    suspend fun deleteEntries(ids: Set<String>): WorldBookDeletedBatch = synchronized(lock) {
        val before = entries.value
        val removed = before.filter { it.id in ids }
        if (removed.isNotEmpty()) {
            mutate { current -> current.filterNot { it.id in ids } }
        }
        WorldBookDeletedBatch(removed, before.map { it.id })
    }

    /** Undo without rolling back unrelated edits made after deletion or duplicating restored IDs. */
    suspend fun restoreDeletedEntries(batch: WorldBookDeletedBatch) {
        if (batch.entries.isEmpty()) return
        synchronized(lock) {
            mutate { current ->
                val existingIds = current.mapTo(mutableSetOf()) { it.id }
                val originalIndex = batch.originalIds.withIndex().associate { it.value to it.index }
                val result = current.toMutableList()
                batch.entries.sortedBy { originalIndex[it.id] ?: Int.MAX_VALUE }.forEach { item ->
                    if (!existingIds.add(item.id)) return@forEach
                    val originalPosition = originalIndex[item.id] ?: Int.MAX_VALUE
                    val successor = result.indexOfFirst { next ->
                        (originalIndex[next.id] ?: Int.MAX_VALUE) > originalPosition
                    }
                    val restored = if (item.folderId != null &&
                        folders.value.none { it.id == item.folderId }) item.copy(folderId = null) else item
                    if (successor >= 0) result.add(successor, restored) else result.add(restored)
                }
                result
            }
        }
    }

    /** Atomically append new entries in original file order. Never replace existing
     * IDs, switches, role overrides or ordering; exact reimports are idempotent. */
    suspend fun importEntries(candidates: List<WorldBookEntry>): WorldBookImportResult {
        require(candidates.all { it.title.isNotBlank() && it.content.isNotBlank() }) {
            "导入内容存在无效的世界书条目"
        }
        var inserted = 0
        var duplicates = 0
        mutate { current ->
            val known = current.mapTo(mutableSetOf()) { it.title.trim() to it.content.trim() }
            val additions = buildList {
                candidates.forEach { entry ->
                    if (!known.add(entry.title.trim() to entry.content.trim())) {
                        duplicates++
                    } else {
                        inserted++
                        // Imported UUIDs are never trusted to overwrite an existing record.
                        add(entry.copy(id = UUID.randomUUID().toString()))
                    }
                }
            }
            current + additions
        }
        return WorldBookImportResult(inserted, duplicates)
    }

    /** Prompt and UI both use this exact persisted order. */
    fun snapshot(): List<WorldBookEntry> = entries.value

    /** Position changes are durable and never shuffle the original entry order. */
    suspend fun setPromptPosition(id: String, position: WorldBookPromptPosition) {
        mutate { current ->
            current.map { entry ->
                if (entry.id == id && entry.promptPosition != position)
                    entry.copy(promptPosition = position, updatedAt = Instant.now())
                else entry
            }
        }
    }

    suspend fun setGlobalEnabled(id: String, enabled: Boolean) {
        mutate { current ->
            current.map { entry ->
                if (entry.id == id) entry.copy(globalEnabled = enabled, updatedAt = Instant.now()) else entry
            }
        }
    }

    suspend fun setCharacterOverride(id: String, characterId: String, enabled: Boolean?) {
        require(characterId.isNotBlank()) { "角色不能为空" }
        mutate { current ->
            current.map { entry ->
                if (entry.id != id) return@map entry
                val nextOverrides = if (enabled == null) {
                    entry.characterOverrides - characterId
                } else {
                    entry.characterOverrides + (characterId to enabled)
                }
                entry.copy(characterOverrides = nextOverrides, updatedAt = Instant.now())
            }
        }
    }

    suspend fun move(id: String, offset: Int) {
        if (offset == 0) return
        mutate { current ->
            val from = current.indexOfFirst { it.id == id }
            if (from < 0) return@mutate current
            val to = (from + offset).coerceIn(0, current.lastIndex)
            if (to == from) return@mutate current
            current.toMutableList().apply {
                val moving = removeAt(from)
                add(to, moving.copy(updatedAt = Instant.now()))
            }
        }
    }

    suspend fun replaceAll(newEntries: List<WorldBookEntry>) {
        mutate { newEntries }
    }

    private fun mutate(transform: (List<WorldBookEntry>) -> List<WorldBookEntry>) {
        synchronized(lock) {
            val next = transform(entries.value)
            prefs?.let { check(it.edit().putString(KEY_ENTRIES, encode(next).toString()).commit()) { "记录保存失败" } }
            entries.value = next
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
                    .put("promptPosition", entry.promptPosition.name)
                    .put("characterOverrides", JSONObject(entry.characterOverrides))
                    .put("folderId", entry.folderId)
                    .put("createdAt", entry.createdAt.toString())
                    .put("updatedAt", entry.updatedAt.toString()),
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
                    val title = item.optString("title").trim()
                    // Preserve the exact text through persistence; whitespace belongs to the original preset.
                    val content = item.optString("content")
                    if (title.isBlank() || content.isBlank()) continue
                    val createdAt = item.optString("createdAt").toInstantOrNow()
                    val overrides = item.optJSONObject("characterOverrides")
                    val overrideMap = buildMap {
                        overrides?.keys()?.forEach { key -> put(key, overrides.optBoolean(key, false)) }
                    }
                    add(
                        WorldBookEntry(
                            id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                            title = title,
                            content = content,
                            globalEnabled = item.optBoolean("globalEnabled", false),
                            characterOverrides = overrideMap,
                            createdAt = createdAt,
                            updatedAt = item.optString("updatedAt").toInstantOrNow(createdAt),
                            folderId = item.optString("folderId")
                                .takeUnless { it.isBlank() || it == "null" },
                            promptPosition = runCatching {
                                WorldBookPromptPosition.valueOf(
                                    item.optString("promptPosition", "NORMAL").uppercase()
                                )
                            }.getOrDefault(WorldBookPromptPosition.NORMAL),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun encodeFolders(values: List<WorldBookFolder>): JSONArray = JSONArray().apply {
        values.forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
    }

    private fun decodeFolders(raw: String?): List<WorldBookFolder> =
        if (raw.isNullOrBlank()) emptyList()
        else runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val id = item.optString("id")
                    val name = item.optString("name").trim()
                    if (id.isNotBlank() && name.isNotBlank()) add(WorldBookFolder(id, name))
                }
            }
        }.getOrDefault(emptyList())

    private companion object {
        const val PREFS_NAME = "lulu_world_book"
        const val KEY_ENTRIES = "entries_v1"
        const val KEY_FOLDERS = "folders_v1"
        const val KEY_HUMAN_CHAT_SEEDED = "human_chat_world_book_seeded_v1"
        const val KEY_HUMAN_CHAT_REVISED_V2 = "human_chat_world_book_revised_v2"
        const val KEY_HUMAN_CHAT_REVISED_V3 = "human_chat_world_book_revised_v3"
        const val KEY_HUMAN_CHAT_REVISED_V4 = "human_chat_world_book_revised_v4"
        const val HUMAN_CHAT_BOOK_ID = "built-in-modern-chat-expression-v1"
        const val HUMAN_CHAT_BOOK_TITLE = "现代聊天与自然表达"
        val HUMAN_CHAT_BOOK_V1_CONTENT = """
            【现代日常聊天的语用参考｜不是角色身份或新任务】
            现代人跟熟人发消息，不是在逐项答题。一个人可以了解事情的全部经过，却只说此刻最想让对方听见的那一小部分；对方有兴趣再往下聊。知道得多不等于每次要讲得多。随口聊天常先给直觉、态度或一个具体发现，不必把原因、背景、后果和建议一次说尽；对方认真询问原因、技术细节或寻求完整帮助时，则可以讲清。
            
            【情绪怎样进入语言】
            真正的感受会改变词语、语序、节奏、标点和停顿：惊讶时可能先蹦出短短一声；着急时可能重复或说话打结；得意时可能故意卖关子；害羞时可能忽然改口，认真时则干净明白。比如“等等等等！”和“等我一下，好不好”传达的是不同的情绪。重复、口头禅、拟声、半句话、突然的自我修正都可能自然出现，但出自这一次真实心情，不当成每轮必演的套路；平淡地说句“嗯，我知道了”也完全正常。
            
            【即时通讯的气泡与文字动作】
            气泡是一次次自然的说话动作，不是把同一篇说明书切成多段。可以一个短反应就独立成泡；下一泡若没有新反应、新意思或情绪转折，可以不写。连续气泡可以依次惊讶、改口、笑、补上一句，彼此有推进；也可以仅有一个气泡。分句依照本人会在哪里停、哪里换气、哪里想让对方先看到一句，不按字数或标点机械切割。
            熟人聊天偶尔用“（假装镇定）”“（把脸埋进枕头）”等括号来做文字里的玩笑或虚拟表演，也会发独立表情包。这只是文本里的表达，不证明真实身体或设备动作；不需要每次加括号。
            
            【每个人有自己的声音】
            本书描述可用的现代口语习惯，不要求所有人物说流行语、卖萌、重复、撒娇或使用同一个节奏。人物自己的性格、语言偏好、当下关系、共同经历和情绪决定要选哪一种。清冷而风趣的人可以嘴角带笑地简短回应，热烈的人可以连发几句；认真或有重要事项时可以详细。不要把知道的事实藏起来，但也不用把知道的一切主动汇报。
        """
        val HUMAN_CHAT_BOOK_V2_CONTENT = """
            【现代聊天与自然表达｜供选择的语用参考】
            熟悉的人聊天通常是在延续眼前的一次互动，而不是完整陈述所有联想。先抓住真正想回应的那一点；一句话说到了就可以停，话题有意思时再自然展开。被认真问到细节、解释或求助时，仍把必要内容说清楚。

            【情绪与语气来自这一次谈话】
            同一个人可以平静、好笑、迟疑、激动，也可以认真。语气来自正在表达的意思和当下感受，不靠持续提高音量证明亲近或活泼。比如普通判断可以说“可能就是味道不对。”，好奇时可以说“你怎么想到这个的？”，真被逗笑时可以说“哈哈，原来你说的是这个”。这些是不同情境的例子，不是要逐句照搬的台词。感叹号适合真有惊讶、喊话或强烈情绪的瞬间；平常玩笑、推测、解释、轻轻吐槽也完全可以用句号、问号、省略或无句末标点。连续几个气泡不意味着每个都要显得兴奋。

            【即时通讯里的气泡】
            气泡是一次次真实说话动作，不是把文章切小。可以先给短反应，再补一句新的观察或玩笑；没有新意思就让对方接话。气泡边界跟随真实停顿和话意变化，不按固定字数、句数或标点切。括号动作、表情包、颜文字和流行语也只是个人可选的表达手段，不是鲜活感的必备装饰。

            【每个人保留自己的声音】
            这是关于现代聊天习惯的参考资料，不是统一人物性格。具体选择由角色自己的设定、现实关系、情绪、历史互动及当前媒介决定。沉静的人可以轻轻一笑，机灵的人可以随口打趣，热情的人也可以安静地认真听。自然感来自具体的理解、反应和变化，不来自重复同一套句式。
        """
        val HUMAN_CHAT_BOOK_V3_CONTENT = """
            【现代即时通讯的社会观察｜文化背景，不是人物性格】
            现代人通过即时消息交流时，既会说完整句子，也会发短词、表情、停顿、图片；对同一件事可以冷静、兴奋、含蓄或幽默。社会交流方式没有统一的人格或口吻，具体说法取决于说话者本人、对象、场合及所处关系。

            【轮流交流与共同活动】
            日常交谈通常由参与者共同推进。一个人有时只回答眼前的问题，有时补充细节，有时把话轮交给对方。轮流猜词、接龙或其他小游戏中，一次参与可能只需要一个词、一个选择或者一句话；规则解释、纠正和策略讨论则是另外的互动。交接话轮并不意味着冷淡，也不是所有对话只能短答。

            【语气、标点与含蓄】
            句号、问号、感叹号、省略号、断句和不写句末标点都见于当代聊天；标点和语气通常与当时表达的意思及情绪程度相呼应。真实惊喜与平常打趣有时用不同力度，普通推测、低声吐槽和认真倾听也属于自然交流。说话者常会留下一些尚未展开的想法，让下一轮的真实回应决定是否继续。

            【资料范围】
            本书只提供社会交流习惯的观察，帮助角色理解别人如何说话、游戏怎样轮流推进；它不是角色专属的说话示例、个性设定、情绪命令或篇幅配额。具体角色的价值、性格不同侧面、关系与个人语言习惯仍来自其本人设定及真实经历。
        """
        val HUMAN_CHAT_BOOK_CONTENT = """
            【现代交流的社会观察｜背景知识，不是人物设定】
            日常对话不是两份独立的完整回答轮流出现，而是参与者共同完成的互动：一句问候期待回应，一个问题引出答案，玩笑等待对方接住；接下来究竟会追问、反对、开玩笑还是换话题，要等对方真实回应才知道。完整理解某件事和当场说出所有理解是两回事。

            【共同语境与省略】
            熟人已有共同经历、称呼、默契和刚刚说过的内容，因此不必把每句话的背景、动机和寓意从头交代。眼前的一句回答、一个词或一个会心的反应可能已经传达所需；下一轮若出现误会、兴趣或新信息，再由双方共同澄清、展开。留白不是故意藏答案，而是把尚未发生的反馈与下一步留给对方。

            【话轮如何自然完成】
            人们一般在一个互动动作成立时交换话轮：问题得到回答、态度被听见、笑点被接住、游戏轮到下一人。话题仍可继续，但这一轮已经暂时完整。接词、猜谜、共同游戏可能只需一个词或选择；认真求助、解释规则、分享见闻、深入谈心、讲故事则可能较长。即时消息可分多个气泡，但连续气泡仍属于一个人的当前发言，并不会自动成为多轮对话。

            【个性、情绪与媒介的差异】
            现代人也会热烈连发、激动感叹、沉默倾听、认真辩论、长篇叙述，语气与标点取决于具体人物的性格、关系、当时情绪和正在进行的活动。句号、问号、感叹号、停顿、颜文字与表情包都是可用手段，不存在适用于每个人的统一口吻和固定篇幅。

            【世界书的范围】
            这些是对社会互动方式的描述，帮助人物理解共同活动、话轮与他人的反应；不是供所有角色模仿的范例台词，也不能重写某个人的价值观、性格、已建立关系或个人表达习惯。角色本人的设定和真实经历决定他如何参与同一种社会交流。
        """
    }
}

private fun String.toInstantOrNow(fallback: Instant = Instant.now()): Instant =
    takeUnless { value -> value.isBlank() || value.equals("null", ignoreCase = true) }
        ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
        ?: fallback
