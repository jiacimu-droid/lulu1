package com.jiacimu.lulu.data

import java.time.Instant

/** Local, read-only delivery cues. These never choose a reply or alter the persona. */
internal object ConversationTurnEconomy {
    fun recentOwnReplies(events: List<SharedTimelineEvent>, characterId: String,
                         sessionId: String, now: Instant): List<String> =
        if (sessionId.isBlank()) emptyList() else events.filter {
            it.characterId == characterId && it.sessionId == sessionId && it.source == "message" &&
                TimelineStatementOwnership.isOwnCharacterStatement(it) && !it.occurredAt.isAfter(now)
        }.sortedBy { it.occurredAt }.takeLast(4).map { it.content }

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
