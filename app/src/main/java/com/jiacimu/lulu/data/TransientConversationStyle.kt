package com.jiacimu.lulu.data

/**
 * Short-lived recipient design for the current conversation.
 *
 * This never becomes a durable personality trait. It only gives the expression layer a small
 * program-owned bias about pace and density, so a character can meet the user's current conversational
 * rhythm without parroting their catchphrases or overwriting the role's own voice.
 */
internal object TransientConversationStyle {
    internal enum class Pace { BRIEF, NORMAL, EXPANSIVE }
    internal enum class TonePressure { LIGHT, SERIOUS, REPAIR }

    internal data class Snapshot(
        val pace: Pace,
        val tonePressure: TonePressure,
        val userBurstCount: Int,
        val prefersShortBurst: Boolean,
    )

    // Input length and connective words describe how the user talks, not how
    // much they want to hear. In particular, a long voice transcript is often
    // one feeling, and a casual "为什么" only asks for the relevant reason.
    private val conciseRequest = Regex("别.{0,4}(?:小作文|长篇|啰嗦|详细)|不要.{0,4}(?:小作文|长篇|详细)|不用.{0,4}(?:展开|解释那么多)|简单说|简短|简洁|一句话|少说点|说重点|留白")
    private val detailRequest = Regex("详细|展开(?:讲|说|聊|分析)|仔细(?:讲|说|分析)|深入(?:聊|讲|分析)|解释清楚|说清楚|深度分析|分析原因|逐[个项条].{0,4}(?:解释|分析|说明)|完整.{0,4}(?:讲|解释|方案)|教程|步骤|排查|多说(?:点|一些)|慢慢说|畅所欲言")
    private val substantiveTask = Regex(
        "(?:怎么|如何).{0,16}(?:调整|安排|规划|制定|解决|实现)|" +
            "(?:帮我|请|能不能|给我|替我).{0,14}(?:分析|对比|比较|计划|方案|讲解|写|取.{0,6}名|起.{0,6}名|列出|列举|整理)"
    )
    private val listRequest = Regex("(?:给|列|取|起|想).{0,5}(?:一批|几个|多些|[两三四五六七八九十\\d]+个).{0,4}(?:名字|例子|方案|建议)")
    private val narrativeRequest = Regex("(?:讲|说|写|续|继续).{0,12}(?:故事|小说|剧情)|(?:故事|小说|剧情).{0,8}(?:继续|接着)|哄睡|续讲")
    private val declinedNarrative = Regex("(?:不想|不要|别|不用).{0,5}(?:故事|小说|剧情|哄睡|续讲)")
    private val continuationRequest = Regex("^(?:继续(?:吧|讲|说|呀)?|接着(?:讲|说)?|然后呢|还有呢|说下去)[。！!～~？?\\s]*$")

    fun allowsExpansion(userText: String): Boolean {
        val text = userText.replace('\n', ' ').trim()
        if (conciseRequest.containsMatchIn(text)) return false
        return detailRequest.containsMatchIn(text) || substantiveTask.containsMatchIn(text) ||
            listRequest.containsMatchIn(text) ||
            continuationRequest.matches(text) ||
            (narrativeRequest.containsMatchIn(text) && !declinedNarrative.containsMatchIn(text))
    }

    fun analyze(userText: String, recentHistory: String = ""): Snapshot {
        val clean = userText.replace("\r\n", "\n").trim()
        val nonSpace = clean.count { !it.isWhitespace() }
        val parts = clean.lines().map(String::trim).filter(String::isNotBlank)
        val burstCount = parts.size.coerceAtLeast(1)
        val repair = Regex(
            "没懂|没\\s*get\\s*到|没有\\s*get\\s*到|你.{0,6}(没|没有).{0,3}(懂|明白|get\\s*到|get)|" +
                "你理解错|不是这个意思|会错意|误会|说偏了|我不是说",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(clean)
        val serious = repair || Regex(
            "难受|委屈|生气|吵架|失望|伤心|崩溃|严肃|认真说|重要|别开玩笑|不舒服|道歉|对不起"
        ).containsMatchIn(clean)
        val pace = when {
            conciseRequest.containsMatchIn(clean) -> Pace.BRIEF
            allowsExpansion(clean) -> Pace.EXPANSIVE
            nonSpace <= 14 -> Pace.BRIEF
            else -> Pace.NORMAL
        }
        val recentShort = recentHistory.lines().takeLast(8)
            .map(String::trim).filter(String::isNotBlank)
            .count { it.length <= 24 } >= 4
        val prefersShortBurst = burstCount >= 2 && parts.count { it.length <= 28 } >= 2 || recentShort

        return Snapshot(
            pace = pace,
            tonePressure = when {
                repair -> TonePressure.REPAIR
                serious -> TonePressure.SERIOUS
                else -> TonePressure.LIGHT
            },
            userBurstCount = burstCount,
            prefersShortBurst = prefersShortBurst,
        )
    }

    /**
     * Non-semantic guidance only. The same prompt is given regardless of word
     * count, rhetorical keywords or a program-estimated emotional tone.
     * The character/expression model decides how seriously to take feedback,
     * whether a full answer is wanted and how many bubbles to use.
     */
    fun context(userText: String, recentHistory: String = "", includeExamples: Boolean = true): String =
        """
        【本轮交流由角色模型自行理解】
        阅读用户完整原话和之前真实对话，结合本人的性格、关系、立场与当前感受，自行选择回应方式、回复篇幅和语气。真实澄清与新信息可以改变理解。
        想当场接话时可留白，认真讨论、详细分析或完整作品则按需要充分表达。自然分成完整气泡，表达多少和怎样交还话轮都由本人结合现场决定。
        已选行动与事实边界沿真实决定和回执保留，具体表达仍属于这个人。
        """.trimIndent()

}
