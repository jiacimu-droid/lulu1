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
        val complexQuestion = clean.count { it == '？' || it == '?' } >= 2 ||
            Regex("首先|其次|然后|另外|还有|为什么|怎么.*以及|具体").containsMatchIn(clean)

        val pace = when {
            nonSpace <= 14 && !complexQuestion -> Pace.BRIEF
            nonSpace >= 90 || complexQuestion -> Pace.EXPANSIVE
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

    fun context(userText: String, recentHistory: String = ""): String {
        val s = analyze(userText, recentHistory)
        val paceGuide = when (s.pace) {
            Pace.BRIEF -> "用户这一拍表达很短；若内容本身不复杂，优先用一个局部动作接住，不为了显得贴心自行扩成长回答。"
            Pace.NORMAL -> "按当前内容需要自然决定长度，不刻意追求短句或长句。"
            Pace.EXPANSIVE -> "用户这一拍包含较多信息或多个问题；可以完整回应必要内容，但仍按局部互动动作组织，不写成说明书式总结。"
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
            append("这是软偏置：不得覆盖角色稳定语言风格、事实完整性、边界或已经确定的 dialogueMove，也不要照抄用户口头禅来假装亲近。")
        }
    }
}
