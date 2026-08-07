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
        val theater = StarWishStores.main.state.value.theaterChapters.mapNotNull { (title, chapters) ->
            if (chapters.isEmpty()) null else BackgroundReadingBook(
                id = "theater:$title",
                title = title,
                content = chapters.sortedBy { it.chapter }.joinToString("\n\n") { chapter ->
                    "第${chapter.chapter}章 ${chapter.title}\n${chapter.content}"
                },
                source = "小剧场",
            )
        }
        return (uploaded + theater).distinctBy(BackgroundReadingBook::id).take(40)
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
