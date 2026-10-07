package com.jiacimu.lulu.study

import android.content.Context
import org.json.JSONArray
import java.security.MessageDigest

internal data class BackgroundReadingBook(
    val id: String,
    val title: String,
    val content: String,
    val source: String,
)

internal data class BackgroundReadingSlice(
    val book: BackgroundReadingBook,
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val totalLength: Int,
    val completed: Boolean,
)

/** Read-only content bridge plus program-owned, per-character reading progress. */
internal object ReadingBackgroundBridge {
    private const val PROGRESS_PREFS = "lulu_background_reading_progress_v1"

    fun books(context: Context): List<BackgroundReadingBook> {
        val uploaded = loadUploaded(context)
        val theaterChapters = StarWishStores.main.state.value.theaterChapters
            .flatMap { (theaterTitle, chapters) ->
                chapters.map { chapter ->
                    BackgroundReadingBook(
                        id = "theater-chapter:${chapter.id}",
                        title = "《$theaterTitle》·第${chapter.chapter}章 ${chapter.title}",
                        content = chapter.content,
                        source = "小剧场章节",
                    ) to chapter.createdAtMillis
                }
            }
            .sortedByDescending { (_, createdAtMillis) -> createdAtMillis }
            .map { (book, _) -> book }
        return interleave(theaterChapters, uploaded)
            .distinctBy(BackgroundReadingBook::id)
            .take(80)
    }

    fun availableBooks(context: Context, characterId: String): List<BackgroundReadingBook> =
        books(context).filter { progress(context, characterId, it) < it.content.length }

    fun progressLabel(context: Context, characterId: String, book: BackgroundReadingBook): String {
        val offset = progress(context, characterId, book)
        val percent = if (book.content.isEmpty()) 100 else (offset * 100 / book.content.length).coerceIn(0, 100)
        return "进度 $offset/${book.content.length}（$percent%）"
    }

    /**
     * Returns the exact next excerpt; commitSlice advances the cursor only after a successful read. A completed book does not
     * silently restart; it disappears from [availableBooks] until the source text changes.
     */
    fun nextSlice(
        context: Context,
        characterId: String,
        bookId: String,
        maxChars: Int = 6_000,
    ): BackgroundReadingSlice? {
        val book = books(context).firstOrNull { it.id == bookId } ?: return null
        if (book.content.isEmpty()) return null
        val start = progress(context, characterId, book)
        if (start >= book.content.length) return null
        val chapterEnd = readingSections(book).firstOrNull { start in it.start until it.end }?.end ?: book.content.length
        val end = minOf(start + maxChars.coerceIn(800, 12_000), chapterEnd, book.content.length)
        return BackgroundReadingSlice(
            book = book,
            text = book.content.substring(start, end),
            startOffset = start,
            endOffset = end,
            totalLength = book.content.length,
            completed = end >= book.content.length,
        )
    }

    fun commitSlice(context: Context, characterId: String, slice: BackgroundReadingSlice): Boolean {
        val prefs = context.applicationContext.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
        val key = progressKey(characterId, slice.book)
        if (prefs.getInt(key, 0) != slice.startOffset) return false
        return prefs.edit().putInt(key, slice.endOffset).commit()
    }

    fun clearCharacter(context: Context, characterId: String) {
        val prefs = context.applicationContext.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("cursor:$characterId:") }.forEach(editor::remove)
        editor.commit()
    }

    private fun progress(context: Context, characterId: String, book: BackgroundReadingBook): Int {
        val value = context.applicationContext.getSharedPreferences(PROGRESS_PREFS, Context.MODE_PRIVATE)
            .getInt(progressKey(characterId, book), 0)
        return value.coerceIn(0, book.content.length)
    }

    private fun progressKey(characterId: String, book: BackgroundReadingBook): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${book.id}\u0000${book.content.length}\u0000${book.content.take(128)}".toByteArray())
            .take(8)
            .joinToString("") { "%02x".format(it) }
        return "cursor:$characterId:${book.id}:$digest"
    }

    /** Keep both generated chapters and uploaded books visible in a bounded model context. */
    private fun interleave(
        theaterChapters: List<BackgroundReadingBook>,
        uploaded: List<BackgroundReadingBook>,
    ): List<BackgroundReadingBook> = buildList {
        val size = maxOf(theaterChapters.size, uploaded.size)
        repeat(size) { index ->
            theaterChapters.getOrNull(index)?.let(::add)
            uploaded.getOrNull(index)?.let(::add)
        }
    }

    private fun loadUploaded(context: Context): List<BackgroundReadingBook> = runCatching {
        val raw = context.getSharedPreferences("lulu_reading_library", Context.MODE_PRIVATE)
            .getString("books_v1", "[]")
        val array = JSONArray(raw)
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val title = item.optString("title").trim()
                val content = item.optString("content").trim()
                if (id.isBlank() || title.isBlank() || content.isBlank()) continue
                add(BackgroundReadingBook(id, title, content, "用户上传"))
            }
        }
    }.getOrDefault(emptyList())
}
