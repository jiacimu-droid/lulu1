package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Runtime personality anatomy for a believable digital person.
 *
 * This is deliberately not another persona template. It tells model-facing code which
 * kinds of information are stable, which are contextual, and which are merely the
 * current conversational state. The separation prevents a momentary mood or a pretty
 * sentence from silently becoming "the personality".
 */
internal object CharacterPersonalityArchitecture {
    fun promptSection(compact: Boolean = false): String = if (compact) """
        【人格运行层级】
        稳定核心：用户设定的身份、价值、边界与长期倾向是先验，不是每轮必须表演的台词。
        情境适应：目标、习惯、相处方式、兴趣和应对策略可随真实经历缓慢变化，并保留证据。
        叙事身份：只从真实时间线形成“我经历过什么、这对我意味着什么”，不补造童年、履历或共同回忆。
        即时状态：注意、评估、情绪、精力、活动与冲动会随场景变化和衰减；同一个人可以在不同场合表现得很不一样。
        会话状态：共同语境、当前话题、尚未回答/修复的事情、对方是否听懂，优先决定这一轮该推进什么。
        表达层：称呼、节奏、词汇、幽默和语音表现把上述状态自然说出来；人格一致不等于每轮同一种说法。
    """.trimIndent() else """
        【人格解剖与运行——稳定的是人，变化的是状态】
        1. 稳定核心（长期、低频变化）：角色身份、核心价值、底线、审美、长期倾向和用户明确设定。它们提供行为先验与边界，不是“遇到任何事都必须表现某个特质”的命令。
        2. 情境化适应（中期、证据驱动）：个人目标、生活任务、兴趣、习惯、应对方式、关系期待、相处策略和经过多次验证的表达习惯。它们可以成长、暂停、反转，但必须能追溯到真实经历与反馈。
        3. 叙事身份（长期连续性）：角色如何理解自己的真实过去、现在与未来，以及哪些经历改变了自己的看法。只允许从权威时间线和已发生事件形成，不把人设背景、小说内容、模型生成或愿望伪装成亲历。
        4. 即时状态（分钟到小时）：此刻注意什么、怎样理解刺激、情绪与并存感受、精力、正在做什么、冲动与克制。即时状态会自然衰减、被新事实覆盖或与旧余波并存，不能反向篡改稳定人格。
        5. 互动状态（逐轮更新）：双方当前共同语境、指代约定、这一轮真正新增的信息、尚未完成的问答/承诺/误解修复、话题焦点以及对方当前是否在场。聊天优先解决局部互动需要，而不是每轮重新解释整段关系。
        6. 表达实现（当场生成）：稳定称呼、个人语感、句长、停顿、幽默、修辞、气泡和语音韵律由前五层共同决定。一个人可以今天健谈、下一刻只回一句；一致性来自价值、记忆、判断和关系连续，而不是重复口头禅。

        【运行原则】
        - 把 Big-Five/HEXACO 一类特质理解为长期概率倾向，而不是固定脚本；真实人物在不同情境下会呈现宽广的状态分布。
        - 允许从多次真实经历形成“情境—反应签名”：同一个人在某类场景下更容易怎样理解、克制或行动，这是一种 if-situation → tendency 的概率倾向，不是 if-X-must-Y 的规则。
        - 对同一刺激先做情境评估：它对当前目标、关系、边界与可控性意味着什么，再产生情绪、动机和行动，不从“人格标签”直接跳到一句台词。
        - 关系不是全局好感度。不同对象分别保留信任、边界、未完成事项和互动习惯；一句好话或一次争执都不应瞬间重写整段关系。
        - 新经历先改变即时状态和局部判断；只有反复、跨场景且有证据的变化才沉淀为习惯/偏好，真正长期稳定后才可能影响更高层。
        - 最近一句漂亮话、一次撒娇、一次冷淡或一次模型失误都不能自动成为人格。先看它是否与长期核心、情境和后续行为一致。
        - “心声”不是第二份台词。只有当注意焦点、情境评估、冲突或未说出口的理由相对上一刻真的发生变化时才存在；若只是有新消息但内部状态没变化，心声应为空。
    """.trimIndent()
}

/**
 * Heart voice is a sparse projection of a private state delta, not a mandatory second answer.
 * The model may propose prose, but the program decides whether it is grounded enough to surface.
 */
internal object CharacterHeartVoicePolicy {
    private val genericWaiting = Regex(
        "(先)?(等(她|他|对方)?|再等等|继续等|不催|先不催|不打扰|先不打扰|给(她|他|对方)点空间|把选择交给(她|他|对方)|让(她|他|对方)自己决定|按(她|他|对方)自己的节奏)"
    )
    private val genericRelationshipAnalysis = Regex(
        "展现.*真实.*自己|尊重.*选择|给.*空间|不想.*压力|不想逼.*回应|让.*自己想清楚"
    )

    fun keepOrBlank(
        thought: String,
        outward: String = "",
        innerLife: JSONObject? = null,
        basis: JSONObject? = null,
        delta: PrivateStateDelta? = null,
        hasFreshEvidence: Boolean,
    ): String {
        val clean = compactThought(thought)
        if (clean.isBlank()) return ""
        val structuredDelta = delta?.meaningful ?: hasStructuredDelta(innerLife)
        val causalBasis = hasCausalBasis(basis)
        val privateResidue = delta?.privateResidue ?: causalBasis
        // When a runtime delta is available it is authoritative: repeating the same emotion/social
        // JSON is not a new mental event. Fresh input alone never authorizes a heart voice.
        if (delta != null && (!delta.meaningful || !privateResidue)) return ""
        if (!structuredDelta && !causalBasis) return ""
        if (!hasFreshEvidence && !structuredDelta) return ""
        if (outward.isNotBlank() && sameMeaning(clean, outward)) return ""
        val normalized = normalize(clean)
        if (!structuredDelta && (genericWaiting.containsMatchIn(normalized) ||
                genericRelationshipAnalysis.containsMatchIn(normalized))) return ""
        return clean
    }

    internal fun hasCausalBasis(basis: JSONObject?): Boolean {
        val value = basis ?: return false
        val focus = value.optString("focus").trim()
        val change = value.optString("change").trim()
        val conflict = value.optString("conflict").trim()
        val unsaidWhy = value.optString("unsaidWhy").trim()
        if (focus.isBlank()) return false
        return listOf(change, conflict, unsaidWhy).count(String::isNotBlank) >= 1
    }

    internal fun hasStructuredDelta(innerLife: JSONObject?): Boolean {
        val state = innerLife ?: return false
        val emotion = state.optJSONObject("emotion")
        if (emotion != null && (emotion.optString("feeling").isNotBlank() ||
                emotion.optString("cause").isNotBlank())) return true
        if ((state.optJSONArray("motives")?.length() ?: 0) > 0) return true
        if ((state.optJSONArray("thoughts")?.length() ?: 0) > 0) return true
        val social = state.optJSONObject("social")
        if (social != null && (social.optString("interpretation").isNotBlank() ||
                social.optString("reason").isNotBlank())) return true
        val correction = state.optJSONObject("selfCorrection")
        if (correction != null && (correction.optString("realization").isNotBlank() ||
                correction.optString("nextTime").isNotBlank())) return true
        return false
    }

    internal fun sameMeaning(left: String, right: String): Boolean {
        val a = normalize(left)
        val b = normalize(right)
        if (a.isBlank() || b.isBlank()) return false
        if (a == b || a.contains(b) || b.contains(a)) return true
        if (a.length < 5 || b.length < 5) return false
        val aPairs = a.windowed(2).toSet()
        val bPairs = b.windowed(2).toSet()
        val denominator = minOf(aPairs.size, bPairs.size).coerceAtLeast(1)
        return aPairs.intersect(bPairs).size.toDouble() / denominator >= 0.60
    }

    private fun compactThought(value: String): String {
        val clean = value.replace("\r\n", "\n")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n+"), " ")
            .trim()
        if (clean.length <= 140) return clean
        val head = clean.take(140)
        val boundary = listOf('。', '！', '？', '!', '?', '；', ';')
            .map(head::lastIndexOf).maxOrNull() ?: -1
        return if (boundary >= 48) head.substring(0, boundary + 1).trim()
        else head.trimEnd('，', ',', '、', '：', ':') + "…"
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_\"'（）()]+"), "")
        .replace(Regex("^(还是|就是|只是|现在|这会儿|此刻|嗯|唔|好吧)+"), "")
}
