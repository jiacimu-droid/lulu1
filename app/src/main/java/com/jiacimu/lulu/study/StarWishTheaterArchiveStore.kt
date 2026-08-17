package com.jiacimu.lulu.study

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

internal data class StarWishTheaterArchive(
    val id: String = UUID.randomUUID().toString(),
    val theater: String,
    val guide: String,
    val chapters: List<StarWishTheaterChapter>,
    val savedAtMillis: Long = System.currentTimeMillis(),
)

/** Independent story snapshots. They never read from or write to chat history or chat memory. */
internal class StarWishTheaterArchiveStore private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutable = MutableStateFlow(load())
    val archives: StateFlow<List<StarWishTheaterArchive>> = mutable.asStateFlow()

    fun save(theater: String, guide: String, chapters: List<StarWishTheaterChapter>) {
        val snapshot = StarWishTheaterArchive(theater = theater, guide = guide, chapters = chapters)
        mutable.value = (listOf(snapshot) + mutable.value).take(MAX_ARCHIVES)
        persist()
    }

    fun delete(id: String) {
        mutable.value = mutable.value.filterNot { it.id == id }
        persist()
    }

    private fun persist() {
        prefs.edit().putString(KEY_ARCHIVES, JSONArray().apply {
            mutable.value.forEach { archive ->
                put(JSONObject().apply {
                    put("id", archive.id)
                    put("theater", archive.theater)
                    put("guide", archive.guide)
                    put("savedAt", archive.savedAtMillis)
                    put("chapters", JSONArray().apply {
                        archive.chapters.forEach { chapter ->
                            put(JSONObject().apply {
                                put("id", chapter.id)
                                put("theater", chapter.theater)
                                put("chapter", chapter.chapter)
                                put("title", chapter.title)
                                put("content", chapter.content)
                                put("userInfluence", chapter.userInfluence)
                                put("createdAt", chapter.createdAtMillis)
                            })
                        }
                    })
                })
            }
        }.toString()).apply()
    }

    private fun load(): List<StarWishTheaterArchive> = runCatching {
        val array = JSONArray(prefs.getString(KEY_ARCHIVES, "[]").orEmpty().ifBlank { "[]" })
        buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val chaptersJson = item.optJSONArray("chapters") ?: JSONArray()
                val chapters = buildList {
                    for (chapterIndex in 0 until chaptersJson.length()) {
                        val chapter = chaptersJson.optJSONObject(chapterIndex) ?: continue
                        add(
                            StarWishTheaterChapter(
                                id = chapter.optString("id").ifBlank { UUID.randomUUID().toString() },
                                theater = chapter.optString("theater"),
                                chapter = chapter.optInt("chapter"),
                                title = chapter.optString("title"),
                                content = chapter.optString("content"),
                                userInfluence = chapter.optString("userInfluence"),
                                createdAtMillis = chapter.optLong("createdAt", System.currentTimeMillis()),
                            ),
                        )
                    }
                }
                add(
                    StarWishTheaterArchive(
                        id = item.optString("id").ifBlank { UUID.randomUUID().toString() },
                        theater = item.optString("theater"),
                        guide = item.optString("guide"),
                        chapters = chapters,
                        savedAtMillis = item.optLong("savedAt", System.currentTimeMillis()),
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    companion object {
        private const val PREFS_NAME = "lulu_star_wish_theater_archives"
        private const val KEY_ARCHIVES = "archives_v1"
        private const val MAX_ARCHIVES = 40
        fun create(context: Context) = StarWishTheaterArchiveStore(context.applicationContext)
    }
}
