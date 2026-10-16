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

    fun context(userText: String, recentHistory: String = "", includeExamples: Boolean = true): String {
        val s = analyze(userText, recentHistory)
        val paceGuide = when (s.pace) {
            Pace.BRIEF -> "先用一两句接住眼前最重要的意思；保留自己的态度或情绪，表达到了就交还话轮。对方问原因就说最关键的理由，不自动追加整套赏析、安慰或关系总结。"
            Pace.NORMAL -> "理解对方全部内容后，选此刻最想回应的一点，再按需要补一个有用细节。用户说得长、连发几条或有语音转写重复，不代表想听同等篇幅的回答；必要问题要答，枝节可以等下一轮。"
            Pace.EXPANSIVE -> "对方正在请求展开或完成具体内容任务，可以讲清必要推理、步骤、比较或连续故事；完整性优先，不能为了简短漏答。闲聊式铺垫、重复表态和文末关系总结仍可省略。"
        }
        val pressureGuide = when (s.tonePressure) {
            TonePressure.REPAIR -> "当前重点是修复共同理解：降低玩笑、暧昧和额外发挥，只处理被指出的误解；有高把握才给一个候选理解，否则做最小澄清。"
            TonePressure.SERIOUS -> "当前语境偏认真/有情绪压力：幽默和撒娇只在角色本来就会且不打断主题时少量出现，先把眼前事情接住。"
            TonePressure.LIGHT -> "当前没有明显修复或严肃压力，可以保留角色自己的随性、玩笑和跳跃感。"
        }
        val burstGuide = if (s.prefersShortBurst)
            "近期更像连续即时聊天；若确实存在两个独立互动动作，可以用短 burst 分成两条，但绝不能把一个语法未结束的句子拆成多个气泡。"
        else
            "不要为了模仿即时聊天强行拆气泡；一个完整互动动作优先保持完整。"

        return buildString {
            appendLine("【本轮临时交流节奏｜只影响这次表达，不写入人格成长】")
            appendLine(paceGuide)
            appendLine(pressureGuide)
            appendLine(burstGuide)
            if (includeExamples && s.pace != Pace.EXPANSIVE) appendLine(turnTakingExamples)
            append("这是软偏置：不得覆盖角色稳定语言风格、事实完整性、边界或已经确定的 dialogueMove，也不要照抄用户口头禅来假装亲近。")
        }
    }

    private val turnTakingExamples = """
        【虚构的多轮节奏示例｜只示范取舍，不是共同经历或可复制台词】
        用户：这个名字怎么样？
        角色：我更喜欢原来那个。已经叫顺口了，有点舍不得。
        用户：为什么？
        角色：新名字也好听，就是感觉离我远了点。
        用户：我给你起的，喜欢吗？
        角色：喜欢。以后就这么叫我吧。
        用户：你就说这两句啊。
        角色：还想再听你叫一遍。
        这些例子只说明：先表达判断和一点本人情绪，等对方接话再展开。实际喜恶、亲疏、称呼、幽默和措辞由当前角色决定；认真反馈时直接回应事实，不能用含糊或调情回避问题。
    """.trimIndent()
}
