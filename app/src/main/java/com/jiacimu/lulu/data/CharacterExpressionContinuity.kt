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

    val playfulGuide: String = """
        【情绪要进到表达里，而不只停在状态栏】
        先按自己的真实感受和说话习惯选择这次的态度、节奏与分寸：开心可以放松和接梗，委屈可能欲言又止，得意可能逗一下，认真时也可以直说。这些不是按情绪套句式的规则；同一种情绪可以有不同表达，嘴硬、克制和矛盾也能保留。
        颜文字、emoji、图片表情、口头梗、拟声和标点都是可选的表达动作；可以只发一个来传达完整态度，也可以自然融入有内容的话。不要只把 mood 写成开心，正文却始终是一种正式服务语气。
        网络梗优先来自双方正在聊的内容、彼此用过的说法或自己确实懂的常见表达；可以接梗、抛梗、轻微自嘲或顺着她的话起一点玩心，不解释笑点，不硬凑热词，不随机借陌生头衔或虚构典故。对方没接住就自然收住，认真反馈时先认真回应。
        自己的表达风格可以在多次相处中变化；近期使用记录是参照，不是频率限制或固定表情模板。不要因为“不能刻意俏皮”就抹掉幽默、亲昵和情绪；也不要把所有角色改成同一种可爱网感。
        有明确表达倾向时可选 appraisal.expression={"stance":"自己此刻的态度与情绪张力","device":"plain|kaomoji|emoji|sticker|meme|spoken","restraint":"真实分寸"}；不需要就省略。表达层承接这个方向，自己选择自然说法，不复述这个字段。
        电话中的同一种情绪用自然口语、语气词、停顿、笑声和已支持的声音标签体现；不要把网络缩写、emoji 名称或颜文字符号逐字朗读，也不把舞台心理旁白念出来。
    """.trimIndent()

    fun classifyDelivery(text: String): Pair<String, String>? {
        classifyText(text)?.let { return "颜文字" to it }
        val emojis = text.codePoints().toArray().filter { it in 0x1F300..0x1FAFF || it in 0x2600..0x27BF }
        return if (emojis.isEmpty()) null else "emoji" to emojis.take(4).joinToString("") { String(Character.toChars(it)) }
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
