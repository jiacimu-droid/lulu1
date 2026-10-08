package com.jiacimu.lulu.study

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

internal data class StarWishPlotCandidate(
    val title: String,
    val worldview: String,
    val hook: String,
    val relationshipCore: String,
    val mainLine: String,
    val hiddenLine: String,
    val foreshadowing: String,
    val emotionalArc: String,
    val proseStyle: String,
    val highlights: String,
    val overview: String,
    val chapters: List<String>,
    val wordCount: String,
    val cast: String = "",
    val characterArcs: String = "",
    val plotSpine: String = "",
    val stagePlan: String = "",
    val endingDirection: String = "",
    val romanceAesthetics: String = "",
    val creativeIntent: String = "",
) {
    fun storyGuide(): String = buildString {
        if (creativeIntent.isNotBlank()) {
            appendLine("【用户原始创作要求（最高优先级）】")
            appendLine(creativeIntent.trim())
            appendLine()
        }
        appendLine("【故事核心】")
        appendLine(overview.trim())
        appendLine("\n【核心看点】")
        appendLine(highlights.trim())
        appendLine("\n【世界前提】")
        appendLine(worldview.trim())
        appendLine("\n【关系底色】")
        appendLine(relationshipCore.trim())
        appendLine("\n【开篇钩子】")
        appendLine(hook.trim())
        appendLine("\n【基调与文风】")
        appendLine(listOf(emotionalArc.trim(), proseStyle.trim()).filter(String::isNotBlank).joinToString("\n"))
        appendLine("\n【每章建议字数】")
        appendLine(wordCount.ifBlank { "1800-3000" })
    }.trim()

    fun storyBible(): StarWishStoryBible = StarWishStoryBible(
        worldview = worldview.trim(),
        overview = overview.trim(),
        hook = hook.trim(),
        highlights = highlights.trim(),
        emotionalArc = emotionalArc.trim(),
        proseStyle = proseStyle.trim(),
        cast = cast.trim(),
        characterArcs = characterArcs.trim(),
        relationshipArc = relationshipCore.trim(),
        plotSpine = plotSpine.trim(),
        mainLine = mainLine.trim(),
        hiddenLine = hiddenLine.trim(),
        foreshadows = foreshadowing.trim(),
        stagePlan = stagePlan.trim(),
        endingDirection = endingDirection.trim(),
        romanceAesthetics = romanceAesthetics.trim(),
        updatedThroughChapter = 0,
    )

    fun chapterPlans(): List<StarWishChapterPlan> = chapters.mapIndexed { index, chapter ->
        val clean = chapter.trim()
        val firstLine = clean.lineSequence().firstOrNull().orEmpty().take(30)
        StarWishChapterPlan(
            number = index + 1,
            title = firstLine.takeIf { it.length in 2..30 } ?: "第 ${index + 1} 章",
            outline = clean,
        )
    }

    fun detailedGuide(): String = buildString {
        appendLine(storyGuide())
        chapters.forEachIndexed { index, chapter ->
            appendLine("\n【第${index + 1}章规划】")
            appendLine(chapter.trim())
        }
    }.trim()
}

internal class StarWishCustomTheaterLibrary private constructor(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("lulu_starwish_custom_theaters", Context.MODE_PRIVATE)

    fun all(): List<StarWishTheaterSeed> {
        val array = runCatching { JSONArray(prefs.getString("items", "[]")) }.getOrDefault(JSONArray())
        return buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index) ?: continue
                val title = item.optString("title").trim()
                if (title.isNotBlank()) add(StarWishTheaterSeed(title, item.optString("prompt")))
            }
        }
    }

    fun hiddenTitles(): Set<String> = prefs.getStringSet("hiddenTitles", emptySet()).orEmpty().toSet()

    fun add(seed: StarWishTheaterSeed) {
        prefs.edit().putStringSet("hiddenTitles", hiddenTitles() - seed.title).apply()
        val items = (all().filterNot { it.title == seed.title } + seed).takeLast(60)
        val array = JSONArray().apply {
            items.forEach { put(JSONObject().put("title", it.title).put("prompt", it.prompt)) }
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    fun delete(title: String) {
        prefs.edit().putStringSet("hiddenTitles", hiddenTitles() + title).apply()
        val array = JSONArray().apply {
            all().filterNot { it.title == title }.forEach {
                put(JSONObject().put("title", it.title).put("prompt", it.prompt))
            }
        }
        prefs.edit().putString("items", array.toString()).apply()
    }

    companion object {
        @Volatile private var instance: StarWishCustomTheaterLibrary? = null
        fun get(context: Context): StarWishCustomTheaterLibrary = instance ?: synchronized(this) {
            instance ?: StarWishCustomTheaterLibrary(context).also { instance = it }
        }
    }
}

/**
 * Compatibility entry for legacy callers. It shares the active one-story
 * generator instead of resurrecting the old three-choice long-form prompt.
 */
internal object StarWishPlotPlanner {
    suspend fun generate(
        characterId: String,
        existingTitle: String?,
        existingGuide: String?,
        direction: String,
    ): Result<List<StarWishPlotCandidate>> =
        StarWishTheaterPlanningEngine.generateStoryCandidate(
            characterId = characterId,
            existingTitle = existingTitle,
            existingGuide = existingGuide,
            direction = direction,
        ).map { listOf(it) }
}

internal fun starWishPlansFromLegacyGuide(guide: String): List<StarWishChapterPlan> {
    val matches = Regex("【第(\\d+)章规划】([\\s\\S]*?)(?=\\n【第\\d+章规划】|$)").findAll(guide).toList()
    return matches.mapIndexed { index, match ->
        val number = match.groupValues.getOrNull(1)?.toIntOrNull() ?: index + 1
        val outline = match.groupValues.getOrNull(2).orEmpty().trim()
        StarWishChapterPlan(
            number = number,
            title = outline.lineSequence().firstOrNull().orEmpty().take(30).ifBlank { "第 $number 章" },
            outline = outline,
        )
    }
}

internal fun starWishGuideWithoutLegacyPlans(guide: String): String =
    guide.substringBefore("【第1章规划】").trim().ifBlank { guide.trim() }
