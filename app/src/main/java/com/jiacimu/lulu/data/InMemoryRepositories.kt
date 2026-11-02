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
                // One editable world-book entry. Owner edits/deletions never re-seed.
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
                // Optional reference must not prevent the entire app from opening
                // if Android temporarily rejects a preferences write.
                val saved = storage.edit().putString(KEY_ENTRIES, encode(initial).toString())
                    .putBoolean(KEY_HUMAN_CHAT_SEEDED, true).commit()
                entries.value = if (saved) initial else stored
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
                    val content = item.optString("content").trim()
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
        const val HUMAN_CHAT_BOOK_ID = "built-in-modern-chat-expression-v1"
        const val HUMAN_CHAT_BOOK_TITLE = "现代聊天与自然表达"
        val HUMAN_CHAT_BOOK_CONTENT = """
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
    }
}

private fun String.toInstantOrNow(fallback: Instant = Instant.now()): Instant =
    takeUnless { value -> value.isBlank() || value.equals("null", ignoreCase = true) }
        ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
        ?: fallback
