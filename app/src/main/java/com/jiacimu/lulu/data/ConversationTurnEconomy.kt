package com.jiacimu.lulu.data

import java.time.Instant

/** Local, read-only delivery cues. These never choose a reply or alter the persona. */
internal object ConversationTurnEconomy {
    fun recentOwnReplies(events: List<SharedTimelineEvent>, characterId: String,
                         sessionId: String, now: Instant): List<String> =
        if (sessionId.isBlank()) emptyList() else events.filter {
            it.characterId == characterId && it.sessionId == sessionId && it.source == "message" &&
                it.evidenceKind == EventEvidenceKind.CharacterStatement && !it.occurredAt.isAfter(now)
        }.sortedBy { it.occurredAt }.takeLast(4).map { it.content }

    val principles: String = """
        【即时接话的轻重】先回应对方这一拍实际在做的事，称呼、撒娇、招呼或抱怨未回应也可以只接一个自然反应。意思到了可以停，问题只在真的好奇或缺少关键事实时提出，不用追加问句维持聊天。
        时间、间隔、在线状态和设备信息帮助理解场景，不是每轮需要播报的新话题。对方没有谈睡眠或时间时，不因系统显示深夜就自动转去问为什么没睡；有真实兴趣和共同语境时仍可自然换话题。
        回复晚了只证明存在间隔，不证明消息卡住、断网、后台忙或角色刚才在做某事。只有本轮程序明确提供的故障回执才支持具体技术原因；未知原因可以不解释，也可以直说刚才没回上，不编借口、不做道歉加报时加追问的整套说明。
    """.trimIndent()

    private val punctuation = Regex("[\\s，。！？!?、；;：:“”‘’…~～—_\\\"'（）()⟪⟫]+")
    private val repetitionRequested = Regex("再说一遍|再说一次|原样|重复|接龙|还是那句|就说")
    private val timeTopic = Regex("几点|时间|晚安|睡|困|熬夜|半夜|凌晨|起床|闹钟|作息|早安")
    private val clockPivot = Regex("这么晚|(?:现在|这会儿|已经|都).{0,6}(?:[一二三四五六七八九十两\\d]+点|\\d{1,2}[:：]\\d{2}|凌晨|半夜)")
    private val deliveryStall = Regex("(?:消息|回复).{0,8}(?:卡了|卡住|卡顿|卡在|延迟了)")
    private val ownNetworkFailure = Regex("(?:我这边|我这里|刚才|刚刚).{0,10}(?:断网|掉线|网络.{0,4}(?:不稳|断了|不好|故障))")
    private val qualified = Regex("不是|并非|不确定|不知道|没有证据|不能确定|可能|也许|是不是|会不会|如果|假如|[“”「」\\\"]")

    private fun opening(text: String): String = text.trim()
        .substringBefore("⟪BUBBLE⟫").substringBefore('\n')
        .split(Regex("[，。！？!?、；;：:…]"), limit = 2).firstOrNull().orEmpty()
        .replace(punctuation, "").lowercase().take(8)

    fun repeatsOpening(reply: String, ownReplies: List<String>, userText: String): Boolean {
        if (repetitionRequested.containsMatchIn(userText)) return false
        val first = opening(reply)
        // Two-character presence replies such as “我在” are ordinary conversation.
        return first.length >= 4 && ownReplies.takeLast(4).any { opening(it) == first }
    }

    fun overloadedClockPivot(userText: String, reply: String): Boolean =
        userText.count { !it.isWhitespace() } in 1..24 &&
            !timeTopic.containsMatchIn(userText) && clockPivot.containsMatchIn(reply) &&
            reply.count { !it.isWhitespace() } >= 70 && reply.count { it == '？' || it == '?' } >= 2

    fun hasUnsupportedDeliveryCause(reply: String, operationalEvidence: String): Boolean =
        reply.split(Regex("[。！？!?\\n]")).any { sentence ->
            !qualified.containsMatchIn(sentence) &&
                ((deliveryStall.containsMatchIn(sentence) && !deliveryStall.containsMatchIn(operationalEvidence)) ||
                    (ownNetworkFailure.containsMatchIn(sentence) && !ownNetworkFailure.containsMatchIn(operationalEvidence)))
        }

    fun context(userText: String, ownReplies: List<String> = emptyList()): String = buildString {
        if (!repetitionRequested.containsMatchIn(userText)) {
            val openings = ownReplies.takeLast(4).map(::opening).filter { it.length >= 4 }.distinct()
            if (openings.isNotEmpty()) {
                appendLine("本人近期真实发言的开头（仅提醒避免机械复用，不是口癖范本）：${openings.joinToString("／")}。同一玩笑可有意重复，普通短确认不必为了求变换词。")
            }
        }
    }.trim()
}
