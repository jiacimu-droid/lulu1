package com.jiacimu.lulu

/**
 * Lightweight, on-device social-context retrieval. Retrieval offers possibilities;
 * the character still decides whether to send a sticker (or no sticker) at all.
 * No embedding endpoint, extra LLM call, or user's image is sent to a server.
 */
internal data class StickerRetrievalContext(
    val userMessage: String = "",
    val recentConversation: String = "",
    val actorMood: String = "",
)

private data class SocialFrame(
    val signals: List<String>,
    val expressions: List<String>,
    val weight: Int = 18,
)

private val socialFrames = listOf(
    SocialFrame(listOf("偷笑", "逗我", "逗你", "调侃", "开玩笑", "坏笑", "憋笑", "笑死", "哈哈哈", "嘿嘿", "嘻嘻", "好好笑", "搞笑", "整活"),
        listOf("坏笑", "偷笑", "得意", "调皮", "捂嘴", "眯眼笑", "俏皮", "逗", "狡黠", "笑出泪")),
    SocialFrame(listOf("抱抱", "贴贴", "亲亲", "想你", "爱你", "好喜欢", "心动", "撒娇", "么么", "mua", "mwah"),
        listOf("喜欢", "爱心", "亲亲", "抱", "贴贴", "心动", "撒娇", "温暖", "陪伴", "示好", "甜甜")),
    SocialFrame(listOf("难过", "委屈", "不开心", "不舒服", "心疼", "想哭", "哭了", "崩溃", "难受", "失落", "沮丧", "好累", "太累", "疲惫", "焦虑", "痛经"),
        listOf("抱", "安慰", "陪伴", "心疼", "关心", "温暖", "难过", "眼泪", "可怜巴巴", "泪光", "委屈", "乖巧"), 22),
    SocialFrame(listOf("好耶", "做到了", "成功了", "通过了", "考过", "满分", "全对", "赢了", "太棒", "恭喜", "庆祝", "终于完成"),
        listOf("庆祝", "开心", "好耶", "派对", "鼓掌", "点赞", "咧嘴", "笑", "骄傲", "得意"), 22),
    SocialFrame(listOf("震惊", "居然", "真的假的", "不会吧", "什么鬼", "我去", "卧槽", "吃惊", "惊呆", "没想到", "吓我"),
        listOf("震惊", "惊讶", "吓", "目瞪", "脑袋炸", "宕机", "张大嘴", "惊", "围观", "吃瓜")),
    SocialFrame(listOf("生气", "气死", "烦死", "无语", "气炸", "讨厌", "不爽", "气鼓鼓", "服了", "别闹"),
        listOf("气鼓鼓", "生气", "严肃", "不高兴", "无奈", "无语", "皱眉", "委屈", "憋屈")),
    SocialFrame(listOf("早安", "早上好", "晚上好", "午安", "你好呀", "嗨呀", "嗨嗨", "哈喽", "拜拜", "再见"),
        listOf("招手", "打招呼", "你好", "欢迎", "挥手", "开心", "亲切")),
    SocialFrame(listOf("晚安", "睡觉", "睡了", "好困", "困死", "打哈欠", "睡不着", "犯困", "睡啦"),
        listOf("困", "睡", "晚安", "打鼾", "做梦", "打哈欠", "陪伴", "安静")),
    SocialFrame(listOf("害羞", "脸红", "不好意思", "尴尬", "社死", "羞死", "害臊"),
        listOf("害羞", "脸红", "捂脸", "害臊", "尴尬", "红脸", "发呆")),
    SocialFrame(listOf("不懂", "没明白", "什么情况", "为什么呀", "怎么回事", "迷惑", "疑惑", "琢磨", "想一想"),
        listOf("疑惑", "思考", "托腮", "发呆", "茫然", "琢磨", "认真考虑")),
    SocialFrame(listOf("厉害", "牛哇", "很棒", "赞同", "说得对", "同意", "支持你", "干得好", "好样的"),
        listOf("点赞", "做得好", "赞同", "好耶", "棒", "开心", "鼓掌")),
)

private val seriousUserSignals = listOf(
    "认真说", "别笑", "不要笑", "不好笑", "不许笑", "别开玩笑", "别闹了", "道歉", "尊重我",
    "难过", "不开心", "不舒服", "难受", "痛经", "崩溃", "委屈", "心疼", "想哭", "哭了", "生气", "气死", "烦死", "失落", "沮丧", "好累", "太累", "疲惫", "焦虑",
)
private val frivolousStickerSignals = listOf("坏笑", "偷笑", "嘲笑", "取笑", "调侃", "笑出泪", "狡黠", "幸灾乐祸", "捉弄", "整蛊")

private fun compactStickerText(source: String, size: Int): String =
    source.takeLast(size).lowercase().filter { it.isLetterOrDigit() }

private fun biGrams(text: String): Set<String> =
    if (text.length < 2) emptySet() else text.windowed(2).toSet()

/**
 * Hybrid retrieval: social-move synonyms > image's actual visual facts and usage
 * description > lexical overlap > optional user preference. No random emotion
 * mapping, and a sad/serious statement is never assumed to be an invitation to tease.
 */
internal fun selectPromptStickers(
    items: List<LuluSticker>, limit: Int, context: StickerRetrievalContext,
): List<LuluSticker> {
    val budget = limit.coerceIn(0, 8)
    if (budget == 0) return emptyList()
    val input = compactStickerText(context.userMessage, 400)
    val recent = compactStickerText(context.recentConversation, 240)
    val mood = compactStickerText(context.actorMood, 60)
    val serious = seriousUserSignals.any(input::contains)
    val active = socialFrames.filter { frame -> frame.signals.any(input::contains) }
        .ifEmpty { socialFrames.filter { frame -> frame.signals.any(mood::contains) } }
        .ifEmpty { socialFrames.filter { frame -> frame.signals.any(recent::contains) }.take(1) }
    val grams = biGrams(input)

    fun score(sticker: LuluSticker): Int {
        val description = compactStickerText(
            sticker.name + " " + sticker.visualDescription + " " + sticker.usageHint, 800,
        )
        val lexical = if (grams.isEmpty()) 0 else biGrams(description).count { it in grams }.coerceAtMost(10) * 2
        val move = active.sumOf { frame ->
            val hits = frame.expressions.count(description::contains)
            when (hits) {
                0 -> 0
                1 -> frame.weight
                else -> frame.weight + (hits - 1).coerceAtMost(3) * 3
            }
        }
        val caution = if (serious && frivolousStickerSignals.any(description::contains)) -45 else 0
        // A favorite never outranks a genuinely relevant reaction solely because it is starred.
        val personal = (if (sticker.favorite) 4 else 0) + (if (sticker.pack == "我的添加") 3 else 0)
        return lexical + move + caution + personal
    }

    val ranked = items.distinctBy(LuluSticker::id).sortedByDescending(::score)
    val result = ranked.take((budget * 3 / 4).coerceAtLeast(1)).toMutableList()
    val picked = result.mapTo(mutableSetOf(), LuluSticker::id)
    // Reserve a couple of places for other packs, so a single prolific pack
    // doesn't make imported stickers or different reactions invisible.
    val packs = ranked.filterNot { it.id in picked }.groupBy(LuluSticker::pack)
        .values.map { it.toMutableList() }
    while (result.size < budget) {
        var added = false
        for (pack in packs) {
            if (result.size >= budget) break
            if (pack.isNotEmpty()) {
                val candidate = pack.removeAt(0)
                if (picked.add(candidate.id)) result += candidate
                added = true
            }
        }
        if (!added) break
    }
    return result
}

/** Keep original callers compatible; new callers should separate the user's words from the actor's mood. */
internal fun selectPromptStickers(items: List<LuluSticker>, limit: Int, cues: String): List<LuluSticker> =
    selectPromptStickers(items, limit, StickerRetrievalContext(userMessage = cues))
