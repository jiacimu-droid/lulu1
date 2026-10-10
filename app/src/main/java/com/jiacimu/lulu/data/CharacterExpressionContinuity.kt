package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Language and sticker use are observable communication acts, not decorative
 * emotion fields. Models can invent a voice, but only actual delivered messages
 * are remembered as part of that voice's evolving habits.
 */
internal object CharacterExpressionContinuity {
    val kaomoji: List<Pair<String, String>> = listOf(
        "偷笑" to "(￣▽￣)", "得意" to "(｡•̀ᴗ-)✧", "开心" to "(๑˃̵ᴗ˂̵)و",
        "害羞" to "(⁄ ⁄•⁄ω⁄•⁄ ⁄)", "疑惑" to "(・・?)", "委屈" to "(｡•́︿•̀｡)",
        "生气" to "(╬ಠ益ಠ)", "喜欢" to "(♡˙︶˙♡)", "贴贴" to "(づ｡◕‿‿◕｡)づ",
        "惊讶" to "(⊙_⊙)", "加油" to "(ง •̀_•́)ง", "流泪" to "(╥﹏╥)",
        "坏笑" to "(￣▽￣)~*", "招手" to "(｡･ω･)ﾉﾞ", "困困" to "(－ω－) zzZ",
    )

    /** Cues are options, never keywords that automatically mandate a reaction. */
    fun guide(): String = buildString {
        appendLine("【自然社交的表达手段】")
        appendLine("可以独立选择：普通文字、短促语气词、停顿或标点、表情/emoji、颜文字、已入库的图片表情，或者这次什么都不发。")
        appendLine("颜文字示例（不是强制模板）：${kaomoji.joinToString("、") { "${it.first} ${it.second}" }}。")
        appendLine("有个人习惯才能稳定形成独特语气；接梗要和实际话题有关，可以自己先开玩笑，也可以不接。允许只发一句‘哈哈’或一个颜文字，不必解释为什么用了它。")
        appendLine("一张图片表情本身就是社交行为，不是一定要搭配解释或敬语；发送前看准确画面描述、自己的感觉、双方熟悉度和最近是否刚发过同一张。")
        appendLine("不要人为安排每三句一个表情，也不要总在同一个语境复制同一套颜文字或固定口头禅；亲昵不等于永远讨好。")
    }

    fun classifyText(text: String): String? = kaomoji.firstOrNull { (_, art) -> text.contains(art) }?.let { (mood, art) ->
        "颜文字:$mood:$art"
    }

    fun record(history: JSONArray?, id: String, kind: String, label: String,
               now: Instant = Instant.now()): JSONArray {
        val entries = recent(history, now).toMutableList()
        if (id.isBlank() || entries.any { it.optString("id") == id }) return JSONArray().apply {
            entries.forEach(::put)
        }
        entries += JSONObject().put("id", id.take(120)).put("kind", kind.take(30))
            .put("label", label.take(120)).put("at", now.toString())
        return JSONArray().apply { entries.takeLast(16).forEach(::put) }
    }

    fun recent(history: JSONArray?, now: Instant = Instant.now()): List<JSONObject> =
        if (history == null) emptyList() else (0 until history.length())
            .mapNotNull(history::optJSONObject).filter {
                val at = runCatching { Instant.parse(it.optString("at")) }.getOrNull()
                at != null && !at.isAfter(now) && Duration.between(at, now) <= Duration.ofDays(3)
            }.takeLast(16)

    fun context(history: JSONArray?, now: Instant = Instant.now()): String {
        val past = recent(history, now).takeLast(5)
        if (past.isEmpty()) return ""
        return buildString {
            appendLine("【最近确实发送过的表达｜本人社交习惯，不是硬性冷却规则】")
            past.forEach {
                appendLine("- ${it.optString("kind")}：${it.optString("label")}")
            }
            appendLine("如果没新的情绪或笑点，无需重复刚发过的同一图片/颜文字；若故意接龙或延续玩笑，重复也可以是有意义的。")
        }.trim()
    }
}
