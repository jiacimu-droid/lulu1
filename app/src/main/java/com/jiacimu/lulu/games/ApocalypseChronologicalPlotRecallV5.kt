package com.jiacimu.lulu.games

import android.content.Context

/**
 * Semantic/vector recall decides which old scenes are useful, but never their reading order.
 * A novel is causal: once the relevant scenes are selected, present them to the model from the
 * earliest scene to the latest scene. The full compressed chronology is supplied separately.
 */
internal suspend fun recallApocalypsePlotMemoryChronologicallyV5(
    context: Context,
    save: ApocalypseV3Save,
    action: String,
    limit: Int = 5,
): String {
    val raw = ApocalypsePlotMemoryRuntimeV5.recall(
        context = context,
        save = save,
        action = action,
        limit = limit,
    ).trim()
    if (raw.isBlank()) return ""

    val sceneRegex = Regex("第(\\d+)幕")
    val entries = raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("-") && sceneRegex.containsMatchIn(it) }
        .distinct()
        .sortedBy { line ->
            sceneRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }
        .toList()

    if (entries.isEmpty()) return raw
    return buildString {
        appendLine("与本幕相关的旧剧情补充（相关性只负责选中；以下严格按原始幕序从早到晚展示）：")
        entries.forEach(::appendLine)
        append("这些摘录只能补充具体旧细节；不得改变完整时间轴里的先后关系，也不得把相关性高理解成发生得更晚或更重要。")
    }
}

internal fun apocalypseDirectorSupplementContextV5(
    chronologicalPlotRecall: String,
    livingWorldContext: String,
): String = buildString {
    if (chronologicalPlotRecall.isNotBlank()) {
        appendLine("【相关旧剧情补充｜已按原始幕序排列】")
        appendLine(chronologicalPlotRecall)
    }
    if (livingWorldContext.isNotBlank()) {
        appendLine("【隐藏活世界导演台｜不是旧剧情召回，也不是玩家知识】")
        appendLine(livingWorldContext)
    }
}.trim()
