package com.jiacimu.lulu.study

/**
 * No extra model call: warn the next writer about repeated stock expressions
 * in the most recent chapters, but never delete or mechanically rewrite prose.
 */
internal object TheaterProseRhythm {
    private val stockExpressions = listOf(
        "野狗", "俊美", "几不可察", "死死", "薄唇", "眼底",
        "似笑非笑", "寒意", "一柄刀", "像一头", "仿佛", "唇角",
        "骤然", "一瞬间", "冷笑",
    )

    fun overusedIn(chapters: List<StarWishTheaterChapter>): List<String> {
        val recent = chapters.takeLast(3).joinToString("\n") { it.content }
        if (recent.isBlank()) return emptyList()
        return stockExpressions.mapNotNull { phrase ->
            val count = Regex(Regex.escape(phrase)).findAll(recent).count()
            phrase.takeIf { count >= 4 }
        }.take(8)
    }
}
