package com.jiacimu.lulu.games

import android.content.Context

private fun apocalypseRollingDirectorGuideV5(save: ApocalypseV3Save): String {
    val director = save.director
    val nextScheduledRefresh = (((save.scene / 6) + 1) * 6).coerceAtLeast(save.scene + 1)
    val activeThreads = director.storyThreads
        .filter { it.status == "active" }
        .sortedByDescending { it.lastTouchedScene }
        .take(3)
    val approachingForeshadows = director.foreshadowLedger
        .filter { it.stage != "paid_off" && it.stage != "abandoned" }
        .sortedWith(compareBy<ApocalypseForeshadowV5> { it.targetPayoffStart }.thenBy { it.targetPayoffEnd })
        .take(2)
    return buildString {
        appendLine("【当前导演连续指导｜一次导演结果供后续数幕复用，不是只管生成它的那一幕】")
        appendLine("当前第${save.scene}幕；常规下一次总导演重审约在第${nextScheduledRefresh}幕${if (director.directorRefreshNeeded) "，但当前状态已要求提前重审" else ""}。在重审前，正文编剧应把下面内容当作滚动方向，而不是每幕重新发明一套主线。")
        appendLine("玩家行动永远优先：指导只决定世界和NPC怎样继续运动，不得强迫玩家接受任务、去某地、相信某人或回到预定路线。玩家偏航后先承认新正史，必要时让directorRefreshNeeded触发提前重排。")
        if (director.longTermPlan.isNotEmpty()) {
            appendLine("未来方向（按当前优先顺序只取前4条，不要求一幕一条机械执行）：")
            director.longTermPlan.take(4).forEach { appendLine("- ${it.take(260)}") }
        }
        if (activeThreads.isNotEmpty()) {
            appendLine("接下来几幕仍在呼吸的剧情线：")
            activeThreads.forEach { thread ->
                appendLine("- ${thread.title}｜现状=${thread.currentState.take(170)}｜下一压力=${thread.nextPressure.take(150)}")
            }
        } else if (director.activeThreads.isNotEmpty()) {
            appendLine("当前明线：${director.activeThreads.take(3).joinToString("｜")}")
        }
        if (approachingForeshadows.isNotEmpty()) {
            appendLine("可继续轻触但不可提前揭底的伏笔：")
            approachingForeshadows.forEach { clue ->
                appendLine("- ${clue.title}｜阶段=${clue.stage}｜只允许沿已有可见证据继续，不得直接说hiddenTruth")
            }
        }
        append("使用方式：普通幕只推进最自然的1个方向，其余保持离屏运行；连续2—3幕允许围绕同一件事逐步发展，也允许被玩家日常行动打断。不要因为看到这份指导就把四条计划一次塞进同一幕。")
    }.trim()
}

/**
 * Semantic/vector recall decides which old scenes are useful, but never their reading order.
 * A novel is causal: once the relevant scenes are selected, present them to the model from the
 * earliest scene to the latest scene. The rolling director guide is deterministic state reuse and
 * therefore adds no extra model request.
 */
internal suspend fun recallApocalypsePlotMemoryChronologicallyV5(
    context: Context,
    save: ApocalypseV3Save,
    action: String,
    limit: Int = 4,
): String {
    val guide = apocalypseRollingDirectorGuideV5(save)
    val raw = ApocalypsePlotMemoryRuntimeV5.recall(
        context = context,
        save = save,
        action = action,
        limit = limit,
    ).trim()
    if (raw.isBlank()) return guide

    val sceneRegex = Regex("第(\\d+)幕")
    val entries = raw.lineSequence()
        .map(String::trim)
        .filter { it.startsWith("-") && sceneRegex.containsMatchIn(it) }
        .distinct()
        .sortedBy { line ->
            sceneRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: Int.MAX_VALUE
        }
        .toList()

    return buildString {
        appendLine(guide)
        appendLine()
        appendLine("【与本幕相关的旧剧情补充｜相关性只负责选中，以下严格按原始幕序从早到晚】")
        if (entries.isEmpty()) {
            appendLine(raw)
        } else {
            entries.forEach(::appendLine)
        }
        append("旧剧情摘录只能补充具体细节；不得改变完整时间轴里的先后关系，也不得把相关性高理解成发生得更晚或更重要。若旧规划与后来正史冲突，以后来真实发生的正史为准。")
    }.trim()
}

internal fun apocalypseDirectorSupplementContextV5(
    chronologicalPlotRecall: String,
    chapterSummaryContext: String,
    livingWorldContext: String,
): String = buildString {
    if (chapterSummaryContext.isNotBlank()) {
        appendLine("【长篇章节压缩档案｜按原始幕序】")
        appendLine(chapterSummaryContext)
    }
    if (chronologicalPlotRecall.isNotBlank()) {
        appendLine("【相关旧剧情与当前滚动导演指导】")
        appendLine(chronologicalPlotRecall)
    }
    if (livingWorldContext.isNotBlank()) {
        appendLine("【隐藏活世界导演台｜不是旧剧情召回，也不是玩家知识】")
        appendLine(livingWorldContext)
    }
}.trim()
