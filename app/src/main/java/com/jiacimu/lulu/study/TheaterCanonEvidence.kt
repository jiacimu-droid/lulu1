package com.jiacimu.lulu.study

/**
 * Rebuilds a small source-backed continuity index from already saved prose.
 * It does not use another model request, or infer events that are not written.
 * Earlier explicit facts remain available after they fall out of the last
 * three chapters normally passed to the writer.
 */
internal object TheaterCanonEvidence {
    private val materialFact = Regex(
        """三千年前|三年前|三日前|多年[以之]前|(?:[一二三四五六七八九十\\d]+)(?:年|月|日|天|个时辰)(?:前|后)|"""
            + """失语|舌头|拔去|割去|毁掉|毁去|摧毁|化作飞灰|化为飞灰|灰飞烟灭|"""
            + """断裂|断了|已死|身亡|陨落|死去|痊愈|复原|再生|治愈|"""
            + """取得|捡到|遗失|丢失|交给|夺走|烧毁|封印|解封|解除契约|立下血契"""
    )

    fun index(chapters: List<StarWishTheaterChapter>, maxChars: Int = 5_400): String {
        if (chapters.isEmpty() || maxChars <= 0) return ""
        val seen = mutableSetOf<String>()
        val lines = mutableListOf<String>()
        var length = 0
        for (chapter in chapters.sortedBy { it.chapter }) {
            val selections = chapter.content.lineSequence()
                .map { it.trim() }
                .filter { line -> line.length in 8..360 && materialFact.containsMatchIn(line) }
                .filter { line -> seen.add(line) }
                .take(3)
                .toList()
            for (line in selections) {
                val source = "第${chapter.chapter}章原文：$line"
                if (length + source.length + 1 > maxChars) return lines.joinToString("\n")
                lines += source
                length += source.length + 1
            }
        }
        return lines.joinToString("\n")
    }

    fun planningText(plan: StarWishChapterPlan): String = buildString {
        append("第${plan.number}章 ${plan.title}：${plan.outline}")
        if (plan.spotlight.isNotBlank()) append("\n【本章高光】${plan.spotlight}")
        if (plan.sceneBeats.isNotBlank()) append("\n【场面节拍】${plan.sceneBeats}")
        if (plan.relationshipBeat.isNotBlank()) append("\n【关系变化】${plan.relationshipBeat}")
    }
}
