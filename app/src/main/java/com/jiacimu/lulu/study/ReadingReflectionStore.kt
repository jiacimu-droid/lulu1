package com.jiacimu.lulu.study

import android.content.Context
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

data class ReadingReflectionRecord(
    val id: String = UUID.randomUUID().toString(), val characterId: String, val bookId: String,
    val bookTitle: String, val chapterTitle: String, val revision: String,
    val startOffset: Int, val endOffset: Int, val reflection: String, val occurredAt: Instant,
)

/** Chapter-adjacent reflections are durable app data, not text squeezed into a chat receipt. */
object ReadingReflectionStore {
    private val generations = mutableMapOf<String, Long>()
    @Synchronized fun generation(characterId: String) = generations[characterId] ?: 0L
    private var application: Context? = null
    private var prefs: android.content.SharedPreferences? = null
    private val mutable = MutableStateFlow<List<ReadingReflectionRecord>>(emptyList())
    val records = mutable.asStateFlow()
    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        application = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences("lulu_reading_reflections", Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs?.getString("records", "[]")) }.getOrDefault(JSONArray())
        mutable.value = (0 until array.length()).mapNotNull { index -> runCatching {
            val o = array.getJSONObject(index)
            ReadingReflectionRecord(o.getString("id"), o.getString("characterId"), o.getString("bookId"),
                o.getString("bookTitle"), o.getString("chapterTitle"), o.getString("revision"),
                o.getInt("startOffset"), o.getInt("endOffset"), o.getString("reflection"), Instant.parse(o.getString("occurredAt")))
        }.getOrNull() }
    }
    @Synchronized fun save(record: ReadingReflectionRecord) {
        check(prefs != null) { "阅读存档尚未初始化" }
        persist(mutable.value.filterNot { it.id == record.id } + record)
    }
    fun get(id: String) = mutable.value.firstOrNull { it.id == id }
    @Synchronized fun clearCharacter(characterId: String) {
        generations[characterId] = generation(characterId) + 1
        persist(mutable.value.filterNot { it.characterId == characterId })
        application?.let { ReadingBackgroundBridge.clearCharacter(it, characterId) }
    }
    @Synchronized internal fun completeRead(record: ReadingReflectionRecord, slice: BackgroundReadingSlice,
        expectedGeneration: Long, afterSave: () -> Unit) {
        check(generation(record.characterId) == expectedGeneration) { "角色记忆已清空，本次阅读结果已取消" }
        val context = checkNotNull(application)
        check(ReadingBackgroundBridge.books(context).any { it.id == record.bookId && readingRevision(it) == record.revision }) {
            "阅读原文已变化，请重新读取"
        }
        save(record)
        if (!ReadingBackgroundBridge.commitSlice(context, record.characterId, slice)) {
            persist(mutable.value.filterNot { it.id == record.id })
            error("阅读进度已变化，请重新读取")
        }
        afterSave()
    }
    private fun persist(records: List<ReadingReflectionRecord>) {
        val array = JSONArray()
        records.forEach { r -> array.put(JSONObject().put("id", r.id).put("characterId", r.characterId)
            .put("bookId", r.bookId).put("bookTitle", r.bookTitle).put("chapterTitle", r.chapterTitle)
            .put("revision", r.revision).put("startOffset", r.startOffset).put("endOffset", r.endOffset)
            .put("reflection", r.reflection).put("occurredAt", r.occurredAt.toString())) }
        check(prefs?.edit()?.putString("records", array.toString())?.commit() != false) { "阅读感想保存失败" }
        mutable.value = records
    }
    /** Existing reading receipts already contain the reflection; retain those when upgrading. */
    fun migrateLegacyHistory(context: Context) {
        initialize(context)
        val books = ReadingBackgroundBridge.books(context)
        val existing = mutable.value.mapTo(mutableSetOf()) { it.id }
        MigratedDomainStores.characters.settings.value.keys.forEach { characterId ->
            SharedExperienceTimeline.all(characterId).filter { it.channel.startsWith("独自阅读《") && it.id !in existing }
                .forEach eventLoop@ { event ->
                    val title = event.channel.substringAfter('《').substringBeforeLast('》')
                    val book = books.firstOrNull { it.title == title } ?: return@eventLoop
                    val reflection = event.content.substringAfter("阅读感想：", "").trim()
                    if (reflection.isBlank()) return@eventLoop
                    val offsets = Regex("字符 (\\d+)—(\\d+)").find(event.content)
                    val start = offsets?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    val end = offsets?.groupValues?.get(2)?.toIntOrNull() ?: book.content.length
                    save(ReadingReflectionRecord(event.id, characterId, book.id, title,
                        readingSections(book).firstOrNull { start in it.start until it.end }?.title ?: title,
                        readingRevision(book), start, end, reflection, event.occurredAt))
                }
        }
    }
}

internal data class ReadingSection(val title: String, val start: Int, val end: Int)
internal fun readingSections(book: BackgroundReadingBook): List<ReadingSection> {
    val headers = Regex("(?m)^\\s*第[零〇一二三四五六七八九十百千万0-9]+[章节回卷][^\\n]*").findAll(book.content).toList()
    if (headers.isEmpty()) return listOf(ReadingSection(book.title, 0, book.content.length))
    return buildList {
        if (headers.first().range.first > 0) add(ReadingSection("序言", 0, headers.first().range.first))
        headers.forEachIndexed { index, h -> add(ReadingSection(h.value.trim(), h.range.first,
            headers.getOrNull(index + 1)?.range?.first ?: book.content.length)) }
    }
}
internal fun readingRevision(book: BackgroundReadingBook): String = java.security.MessageDigest.getInstance("SHA-256")
    .digest(book.content.toByteArray()).joinToString("") { "%02x".format(it) }
