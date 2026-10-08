package com.jiacimu.lulu.study

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** Preserve quoted prose. Extract complete payloads, then handle common provider wrappers. */
internal fun theaterPlanningValue(raw: String): Any? {
    val clean = raw.trim().removePrefix("```json").removePrefix("```JSON").removePrefix("```").removeSuffix("```").trim()
    fun parse(input: String): Any? = runCatching { JSONTokener(input).nextValue() }.getOrNull()
    parse(clean)?.takeIf { it is JSONObject || it is JSONArray }?.let { return it }
    if (clean.startsWith('"')) (parse(clean) as? String)?.takeIf { it != clean }?.let {
        theaterPlanningValue(it)?.let { value -> return value }
    }
    for (start in clean.indices.filter { clean[it] == '{' || clean[it] == '[' }) {
        val stack = mutableListOf<Char>()
        var quoted = false
        var escaped = false
        for (end in start until clean.length) {
            val c = clean[end]
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
                continue
            }
            when (c) {
                '"' -> quoted = true
                '{', '[' -> stack.add(c)
                '}', ']' -> {
                    if (stack.isEmpty() || stack.removeAt(stack.lastIndex) != if (c == '}') '{' else '[') break
                    if (stack.isEmpty()) {
                        parse(clean.substring(start, end + 1))?.let { return it }
                        break
                    }
                }
            }
        }
    }
    return null
}

internal fun theaterPlanningObjects(value: Any?, matches: (JSONObject) -> Boolean): List<JSONObject> = when (value) {
    is JSONObject -> if (matches(value)) listOf(value) else value.keys().asSequence()
        .flatMap { theaterPlanningObjects(value.opt(it), matches).asSequence() }.toList()
    is JSONArray -> (0 until value.length()).flatMap { theaterPlanningObjects(value.opt(it), matches) }
    is String -> theaterPlanningValue(value)?.let { theaterPlanningObjects(it, matches) }.orEmpty()
    else -> emptyList()
}

private fun planningKey(value: String) = value.trim().replace(Regex("^[一二三四五六七八九十0-9]+[、.．)）]\\s*"), "")
    .replace(Regex("[\\s【】*#/／·:：()（）_-]"), "").lowercase()

private val planningAliases = listOf(
    listOf("title", "标题", "书名", "name"),
    listOf("worldview", "世界观", "世界设定", "世界前提", "世界规则"),
    listOf("overview", "故事总纲", "总纲", "总览", "故事核心"),
    listOf("hook", "核心钩子", "开篇钩子", "钩子"),
    listOf("cast", "人物", "人物设定", "人物卡", "人物与人设", "主要人物"),
    listOf("experienceFocus", "核心阅读体验", "爽点执行", "阅读快感", "体验重心"),
    listOf("appearanceDesign", "人物视觉档案", "容貌和身体细节", "人物外貌", "视觉设计"),
    listOf("relationshipDynamics", "双向关系动力", "人物关系张力", "关系动力"),
    listOf("characterArcs", "人物成长", "成长弧", "人物成长弧"),
    listOf("relationshipArc", "relationshipCore", "长期感情线", "感情线", "关系线", "关系主线"),
    listOf("plotSpine", "故事脉络", "剧情脉络", "长线脉络", "故事脉络 / 主线"),
    listOf("mainLine", "明线", "主线", "主线目标"),
    listOf("hiddenLine", "暗线", "暗线真相"),
    listOf("foreshadows", "foreshadowing", "伏笔", "伏笔系统", "伏笔明细"),
    listOf("stagePlan", "阶段规划", "阶段高潮", "阶段节奏", "阶段高潮与节奏"),
    listOf("endingDirection", "结局方向", "结局"),
    listOf("romanceAesthetics", "感情描写", "审美执行", "感情戏与人物描写"),
    listOf("emotionalArc", "情绪曲线", "情感曲线"),
    listOf("proseStyle", "文风", "叙事风格", "文风执行"),
    listOf("highlights", "核心看点", "亮点", "爽点"),
)

private fun planningDisplay(value: Any?): String = when (value) {
    null, JSONObject.NULL -> ""
    is JSONObject -> value.keys().asSequence().map { "$it：${planningDisplay(value.opt(it))}" }.joinToString("\n")
    is JSONArray -> (0 until value.length()).map { planningDisplay(value.opt(it)) }.filter(String::isNotBlank).joinToString("\n\n")
    else -> theaterPlanningTypography(value.toString())
}

internal fun theaterPlanningText(root: JSONObject, vararg keys: String): String {
    val wanted = keys.map(::planningKey).toMutableSet()
    planningAliases.filter { group -> group.any { planningKey(it) in wanted } }.forEach { wanted.addAll(it.map(::planningKey)) }
    return root.keys().asSequence().filter { planningKey(it) in wanted }
        .map { planningDisplay(root.opt(it)) }.firstOrNull(String::isNotBlank).orEmpty()
}

internal fun theaterPlanningSections(raw: String, aliases: List<String> = planningAliases.flatten()): JSONObject {
    val known = aliases.map(::planningKey).toSet()
    val headers = Regex("(?m)^\\s*(?:#{1,6}\\s*|【|\\*\\*)?([^\\n:：]{2,40}?)(?:】|\\*\\*)?\\s*(?:[:：]\\s*|$)")
        .findAll(raw).filter { planningKey(it.groupValues[1]) in known }.toList()
    return JSONObject().apply {
        headers.forEachIndexed { index, match ->
            val value = raw.substring(match.range.last + 1, headers.getOrNull(index + 1)?.range?.first ?: raw.length).trim()
            if (value.isNotBlank()) put(match.groupValues[1].trim(), value)
        }
    }
}

internal fun StarWishStoryBible.withMissingFieldsFrom(previous: StarWishStoryBible?): StarWishStoryBible =
    if (previous == null) this else copy(
        worldview = worldview.ifBlank { previous.worldview },
        overview = overview.ifBlank { previous.overview },
        hook = hook.ifBlank { previous.hook },
        highlights = highlights.ifBlank { previous.highlights },
        emotionalArc = emotionalArc.ifBlank { previous.emotionalArc },
        proseStyle = proseStyle.ifBlank { previous.proseStyle },
        cast = cast.ifBlank { previous.cast },
        characterArcs = characterArcs.ifBlank { previous.characterArcs },
        relationshipArc = relationshipArc.ifBlank { previous.relationshipArc },
        plotSpine = plotSpine.ifBlank { previous.plotSpine },
        mainLine = mainLine.ifBlank { previous.mainLine },
        hiddenLine = hiddenLine.ifBlank { previous.hiddenLine },
        foreshadows = foreshadows.ifBlank { previous.foreshadows },
        stagePlan = stagePlan.ifBlank { previous.stagePlan },
        endingDirection = endingDirection.ifBlank { previous.endingDirection },
        romanceAesthetics = romanceAesthetics.ifBlank { previous.romanceAesthetics },
        experienceFocus = experienceFocus.ifBlank { previous.experienceFocus },
        appearanceDesign = appearanceDesign.ifBlank { previous.appearanceDesign },
        relationshipDynamics = relationshipDynamics.ifBlank { previous.relationshipDynamics },
    )

/** One fixed framework shared by the director prompt and the visible planning page. */
internal val theaterBibleFields = linkedMapOf(
    "worldview" to "世界观", "overview" to "故事总纲", "hook" to "核心钩子", "highlights" to "核心看点",
    "experienceFocus" to "本书阅读体验与爽点兑现", "appearanceDesign" to "人物视觉与气质档案",
    "relationshipDynamics" to "双向关系动力与权力张力",
    "cast" to "人物与人设", "characterArcs" to "人物成长弧", "relationshipArc" to "长期感情线", "plotSpine" to "故事脉络",
    "mainLine" to "明线", "hiddenLine" to "暗线", "foreshadows" to "伏笔明细", "stagePlan" to "阶段高潮与节奏",
    "endingDirection" to "结局方向", "emotionalArc" to "情绪曲线", "proseStyle" to "文风执行", "romanceAesthetics" to "感情戏与人物描写",
)

internal fun StarWishStoryBible.fieldValues(): Map<String, String> = linkedMapOf(
    "worldview" to worldview, "overview" to overview, "hook" to hook, "highlights" to highlights,
    "experienceFocus" to experienceFocus, "appearanceDesign" to appearanceDesign,
    "relationshipDynamics" to relationshipDynamics,
    "cast" to cast, "characterArcs" to characterArcs, "relationshipArc" to relationshipArc, "plotSpine" to plotSpine,
    "mainLine" to mainLine, "hiddenLine" to hiddenLine, "foreshadows" to foreshadows, "stagePlan" to stagePlan,
    "endingDirection" to endingDirection, "emotionalArc" to emotionalArc, "proseStyle" to proseStyle, "romanceAesthetics" to romanceAesthetics,
)
