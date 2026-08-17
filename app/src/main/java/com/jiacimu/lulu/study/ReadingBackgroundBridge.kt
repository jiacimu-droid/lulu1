package com.jiacimu.lulu.study

import android.content.Context
import org.json.JSONArray

internal data class BackgroundReadingBook(
    val id: String,
    val title: String,
    val content: String,
    val source: String,
)

/** Read-only bridge used when a character chooses to spend a perception cycle reading. */
internal object ReadingBackgroundBridge {
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
