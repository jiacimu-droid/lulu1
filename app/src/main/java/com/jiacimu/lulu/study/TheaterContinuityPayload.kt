package com.jiacimu.lulu.study

internal val theaterLedgerFields = linkedMapOf(
    "summary" to listOf("剧情摘要", "摘要", "当前剧情", "summary"),
    "characters" to listOf("人物当前状态", "人物状态", "characters"),
    "worldState" to listOf("世界与地点状态", "世界状态", "worldState"),
    "relationships" to listOf("当前关系", "人物关系", "relationships"),
    "openThreads" to listOf("正在推进的线", "未完线索", "openThreads"),
    "foreshadows" to listOf("伏笔状态", "伏笔", "foreshadows"),
    "keyItems" to listOf("关键物品", "物品状态", "keyItems"),
    "hardFacts" to listOf("硬事实", "已确认事实", "hardFacts"),
)

internal fun parseTheaterLedger(raw: String, chapterNumber: Int, previous: StarWishStoryLedger? = null): StarWishStoryLedger {
    val roots = theaterPlanningObjects(theaterPlanningValue(raw)) { root ->
        theaterLedgerFields.any { (key, names) -> theaterPlanningText(root, key, *names.toTypedArray()).isNotBlank() }
    }
    val root = roots.firstOrNull() ?: theaterPlanningSections(raw, theaterLedgerFields.values.flatten())
    fun text(key: String) = theaterPlanningText(root, key, *theaterLedgerFields.getValue(key).toTypedArray())
    check(listOf("summary", "characters", "worldState").any { text(it).isNotBlank() }) { "连续性档案尚未返回可识别内容" }
    return StarWishStoryLedger(summary = text("summary").ifBlank { previous?.summary.orEmpty() },
        characters = text("characters").ifBlank { previous?.characters.orEmpty() },
        worldState = text("worldState").ifBlank { previous?.worldState.orEmpty() },
        relationships = text("relationships").ifBlank { previous?.relationships.orEmpty() },
        openThreads = text("openThreads").ifBlank { previous?.openThreads.orEmpty() },
        foreshadows = text("foreshadows").ifBlank { previous?.foreshadows.orEmpty() },
        keyItems = text("keyItems").ifBlank { previous?.keyItems.orEmpty() },
        hardFacts = text("hardFacts").ifBlank { previous?.hardFacts.orEmpty() },
        updatedThroughChapter = chapterNumber)
}

/** Exact saved prose is a durable fallback; it never invents a replacement summary or new facts. */
internal fun theaterLedgerFromEvidence(previous: StarWishStoryLedger, chapters: List<StarWishTheaterChapter>): StarWishStoryLedger {
    val missing = chapters.filter { it.chapter > previous.updatedThroughChapter }
    val evidence = missing.joinToString("\n\n") { chapter ->
        val body = if (chapter.content.length <= 5_000) chapter.content else
            chapter.content.take(2_000) + "\n【中段略，完整正文已保存】\n" + chapter.content.takeLast(3_000)
        "【第${chapter.chapter}章 ${chapter.title} · 已保存正文证据】\n$body"
    }
    val previousSummary = if (previous.summary.length <= 24_000) previous.summary else
        previous.summary.take(8_000) + "\n【较早正文片段略，完整章节仍已保存】\n" + previous.summary.takeLast(16_000)
    val summary = listOf("连续性字段尚未整理，以下原文是最高事实；缺失状态不能猜测，旧字段不得覆盖更晚正文。",
            previousSummary, evidence).filter(String::isNotBlank).joinToString("\n\n")
    return previous.copy(
        summary = if (summary.length <= 32_000) summary else summary.take(8_000) + "\n【完整正文仍已保存】\n" + summary.takeLast(24_000),
        updatedThroughChapter = maxOf(previous.updatedThroughChapter, chapters.maxOfOrNull { it.chapter } ?: 0),
        evidenceOnly = true,
    )
}
