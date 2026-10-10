package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject

internal enum class DialogueMoveType(val wire: String) {
    ACKNOWLEDGE("acknowledge"),
    ANSWER("answer"),
    ASK("ask"),
    BACKCHANNEL("backchannel"),
    SELF_REPAIR("self_repair"),
    OTHER_INITIATED_REPAIR("other_initiated_repair"),
    CANDIDATE_UNDERSTANDING("candidate_understanding"),
    DISAGREE("disagree"),
    TEASE("tease"),
    REASSURE("reassure"),
    TOPIC_SHIFT("topic_shift"),
    DEFER("defer"),
    DECLINE("decline"),
    SILENCE("silence"),
    CLOSE("close"),
    SHARE("share");

    companion object {
        fun fromWire(value: String): DialogueMoveType? =
            values().firstOrNull { it.wire == value.trim().lowercase() }
    }
}

internal enum class RepairFormat { NONE, OPEN, CANDIDATE }

internal data class DialogueMovePlan(
    val type: DialogueMoveType,
    val repairFormat: RepairFormat = RepairFormat.NONE,
    val target: String = "",
    val candidate: String = "",
    val confidence: Double = 0.0,
    val contentIntent: String = "",
    val maxBubbles: Int = 2,
) {
    val isRepair: Boolean
        get() = type == DialogueMoveType.OTHER_INITIATED_REPAIR ||
            type == DialogueMoveType.CANDIDATE_UNDERSTANDING ||
            type == DialogueMoveType.SELF_REPAIR
}

/**
 * Conversation Analysis runtime.
 *
 * The model may propose a move, but a direct user correction is classified by program logic before
 * surface wording. This prevents a repair turn from expanding into apology + guessing + flirting +
 * another question merely because the language model tries to be maximally helpful.
 */
internal object DialogueMoveEngine {
    private val repairSignal = Regex(
        "(不是(这个|那|我说的)?意思|我不是(这个|那)意思|你.{0,6}(没|没有)(懂|明白|get到|get)|" +
            "你.{0,8}(理解错|会错意|想错|猜错|听错)|不是让你|我说的是|我指的是|你理解偏了|你没get到)",
        setOf(RegexOption.IGNORE_CASE),
    )

    fun userInitiatesRepair(userText: String): Boolean =
        repairSignal.containsMatchIn(userText.replace("\n", " ").trim())

    fun plannerConstraint(userText: String): String {
        if (!userInitiatesRepair(userText)) return ""
        return """
            【程序识别到：用户正在发起理解修复】
            这不是新话题，也不是邀请表演歉意。上一轮你对用户意思的解释应视为 disputed/rejected。
            本轮 dialogueMove 必须是 other_initiated_repair 或 candidate_understanding。
            只有存在单一、上下文强支持且 confidence >= 0.72 的候选理解时才允许 candidate_understanding；
            否则使用 open repair。不要枚举多个猜测，不要增加惩罚、将功补过、暗号、职位、游戏化剧本，
            不要在修复完成前扩写关系分析、撒娇或连续追问。
        """.trimIndent()
    }

    fun resolve(raw: JSONObject?, speechIntent: String, userText: String): DialogueMovePlan {
        val typeRaw = raw?.optString("type").orEmpty()
        val proposed = DialogueMoveType.fromWire(typeRaw)
        val candidate = raw?.optString("candidate").orEmpty().trim().take(220)
        val target = raw?.optString("target").orEmpty().trim().take(180)
        val confidence = raw?.optDouble("confidence", 0.0)?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0) ?: 0.0
        val intent = raw?.optString("contentIntent").orEmpty().trim()
            .ifBlank { speechIntent.trim() }.take(500)

        if (userInitiatesRepair(userText)) {
            val useCandidate = candidate.isNotBlank() && confidence >= 0.72
            return DialogueMovePlan(
                type = if (useCandidate) DialogueMoveType.CANDIDATE_UNDERSTANDING
                else DialogueMoveType.OTHER_INITIATED_REPAIR,
                repairFormat = if (useCandidate) RepairFormat.CANDIDATE else RepairFormat.OPEN,
                target = target,
                candidate = if (useCandidate) candidate else "",
                confidence = confidence,
                contentIntent = intent.ifBlank {
                    if (useCandidate) "只提出一个最可能的理解让对方确认"
                    else "承认刚才会错意，并把修复权交还给对方"
                },
                maxBubbles = 1,
            )
        }

        val resolvedType = proposed ?: when (raw?.optString("interactionMove").orEmpty().lowercase()) {
            "acknowledge" -> DialogueMoveType.ACKNOWLEDGE
            "answer" -> DialogueMoveType.ANSWER
            "ask" -> DialogueMoveType.ASK
            "repair" -> DialogueMoveType.SELF_REPAIR
            "share" -> DialogueMoveType.SHARE
            "tease" -> DialogueMoveType.TEASE
            "decline" -> DialogueMoveType.DECLINE
            "shift" -> DialogueMoveType.TOPIC_SHIFT
            "silent" -> DialogueMoveType.SILENCE
            else -> DialogueMoveType.ANSWER
        }
        val moveCap = when (resolvedType) {
            DialogueMoveType.BACKCHANNEL, DialogueMoveType.ACKNOWLEDGE,
            DialogueMoveType.CLOSE, DialogueMoveType.DEFER, DialogueMoveType.DECLINE,
            DialogueMoveType.SELF_REPAIR, DialogueMoveType.OTHER_INITIATED_REPAIR,
            DialogueMoveType.CANDIDATE_UNDERSTANDING -> 1
            DialogueMoveType.TEASE, DialogueMoveType.DISAGREE, DialogueMoveType.REASSURE,
            DialogueMoveType.TOPIC_SHIFT, DialogueMoveType.ASK -> 2
            DialogueMoveType.ANSWER, DialogueMoveType.SHARE -> 3
            DialogueMoveType.SILENCE -> 1
        }
        val requestedBubbles = raw?.optInt("maxBubbles", moveCap)?.coerceIn(1, 3) ?: moveCap
        return DialogueMovePlan(
            type = resolvedType,
            repairFormat = when (raw?.optString("repairFormat").orEmpty().lowercase()) {
                "open" -> RepairFormat.OPEN
                "candidate" -> RepairFormat.CANDIDATE
                else -> RepairFormat.NONE
            },
            target = target,
            candidate = candidate,
            confidence = confidence,
            contentIntent = intent,
            maxBubbles = minOf(requestedBubbles, moveCap),
        )
    }

    fun expressionConstraint(plan: DialogueMovePlan): String = buildString {
        appendLine("【程序已确定的会话动作】${plan.type.wire}")
        if (plan.target.isNotBlank()) appendLine("修复/回应目标：${plan.target}")
        if (plan.contentIntent.isNotBlank()) appendLine("内容意图：${plan.contentIntent}")
        if (plan.repairFormat == RepairFormat.CANDIDATE && plan.candidate.isNotBlank()) {
            appendLine("唯一允许提出的候选理解：${plan.candidate}")
            appendLine("该候选仅供确认，不能当成已经理解正确。")
        }
        appendLine("最多发送 ${plan.maxBubbles} 个气泡；每个气泡必须是一个局部互动动作，而不是长答案的排版切片。")
        if (plan.isRepair) {
            appendLine("当前首先完成局部修复。不要枚举第二个候选，不要把误解改写成惩罚/将功补过/暗号/撒娇剧本。")
            appendLine("不要连续追问，不要在这一拍额外做关系总结。说到足够让对方继续修复就停。")
        }
    }.trim()
}

internal data class PrivateStateDelta(
    val focusChanged: Boolean,
    val appraisalChanged: Boolean,
    val emotionChanged: Boolean,
    val motiveChanged: Boolean,
    val conflictChanged: Boolean,
    val commonGroundChanged: Boolean,
    val privateResidue: Boolean,
    val fingerprint: String,
) {
    val meaningful: Boolean
        get() = focusChanged || appraisalChanged || emotionChanged || motiveChanged ||
            conflictChanged || commonGroundChanged
}

/**
 * Computes novelty from causal state, not from prose novelty.
 *
 * The caller supplies the already persisted private state and the model's proposed bounded update.
 * Repeating the same emotion/social/thought JSON no longer counts as a fresh delta simply because
 * those fields are present again.
 */
internal object ConversationGroundingEngine {
    fun beforeTurn(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        userText: String,
    ) {
        when {
            DialogueMoveEngine.userInitiatesRepair(userText) -> {
                CharacterInnerLifeStore.rejectGroundingCandidates(
                    characterId = characterId,
                    conversationKey = conversationKey,
                    evidenceId = evidenceId,
                    reason = "用户明确否定了上一轮理解",
                )
            }
            userConfirmsCandidate(userText) -> {
                CharacterInnerLifeStore.acceptLatestGroundingCandidate(
                    characterId = characterId,
                    conversationKey = conversationKey,
                    evidenceId = evidenceId,
                )
            }
        }
    }

    internal fun userConfirmsCandidate(userText: String): Boolean {
        val normalized = userText.lowercase()
            .replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_\\\"'（）()]+"), "")
        return normalized in setOf(
            "对", "对的", "嗯对", "对对对", "是的", "没错",
            "就是这个意思", "对就是这个意思", "这次对了", "这回对了", "你终于懂了",
        )
    }

    fun afterDecision(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        plan: DialogueMovePlan,
    ) {
        if (plan.type == DialogueMoveType.CANDIDATE_UNDERSTANDING &&
            plan.candidate.isNotBlank()) {
            CharacterInnerLifeStore.recordGroundingCandidate(
                characterId = characterId,
                conversationKey = conversationKey,
                evidenceId = evidenceId,
                content = plan.candidate,
                confidence = plan.confidence,
            )
        }
    }

    fun context(characterId: String, conversationKey: String): String =
        CharacterInnerLifeStore.groundingContext(characterId, conversationKey)
}

internal object PrivateStateDeltaEngine {
    fun evaluate(
        previous: JSONObject,
        proposal: JSONObject?,
        appraisal: JSONObject?,
        basis: JSONObject?,
        thought: String,
    ): PrivateStateDelta {
        val previousEmotion = previous.optJSONObject("emotion")
        val proposedEmotion = proposal?.optJSONObject("emotion")
        val emotionChanged = proposedEmotion != null && !sameEmotion(previousEmotion, proposedEmotion)

        val motiveChanged = proposal?.optJSONArray("motives")?.let(::hasRealMotiveChange) == true
        val conflictChanged = proposal?.optJSONArray("thoughts")?.let { thoughts ->
            hasNovelThought(previous.optJSONArray("thoughts"), thoughts)
        } == true

        val transitions = previous.optJSONArray("causalTransitions")
        val latestTransition = transitions?.optJSONObject((transitions.length() - 1).coerceAtLeast(0))
        val previousBasis = latestTransition?.optJSONObject("innerThoughtBasis")
        val focus = basis?.optString("focus").orEmpty().trim()
        val focusChanged = focus.isNotBlank() &&
            normalize(focus) != normalize(previousBasis?.optString("focus").orEmpty())

        val previousAppraisal = latestTransition?.optJSONObject("appraisal")
        val appraisalChanged = appraisal != null && listOf("meaning", "responseAim", "uncertainty", "interactionMove")
            .any { key ->
                val next = appraisal.optString(key).trim()
                next.isNotBlank() && normalize(next) != normalize(previousAppraisal?.optString(key).orEmpty())
            }

        val interactions = previous.optJSONObject("interactions")
        val interactionStates = interactions?.let { root ->
            root.keys().asSequence().toList().mapNotNull(root::optJSONObject)
        }.orEmpty()
        val oldInteraction = interactionStates.maxByOrNull { it.optString("updatedAt") }
        val commonGround = appraisal?.optString("commonGroundUpdate").orEmpty().trim()
        val commonGroundChanged = commonGround.isNotBlank() &&
            normalize(commonGround) != normalize(oldInteraction?.optString("lastCommonGroundUpdate").orEmpty())

        val privateResidue = thought.isNotBlank() && (
            basis?.optString("unsaidWhy").orEmpty().isNotBlank() ||
                basis?.optString("conflict").orEmpty().isNotBlank() ||
                emotionChanged || motiveChanged || conflictChanged
            )

        val fingerprintMaterial = buildString {
            append("focus=").append(normalize(focus)).append('|')
            append("meaning=").append(normalize(appraisal?.optString("meaning").orEmpty())).append('|')
            append("emotion=").append(normalize(proposedEmotion?.optString("feeling").orEmpty())).append(':')
                .append(normalize(proposedEmotion?.optString("cause").orEmpty())).append('|')
            append("motive=").append(motiveSignature(proposal?.optJSONArray("motives"))).append('|')
            append("conflict=").append(thoughtSignature(proposal?.optJSONArray("thoughts"))).append('|')
            append("social=").append(socialSignature(proposal?.optJSONObject("social"))).append('|')
            append("residue=").append(normalize(basis?.optString("unsaidWhy").orEmpty()))
        }
        val fingerprint = fingerprintMaterial.hashCode().toUInt().toString(16)

        return PrivateStateDelta(
            focusChanged = focusChanged,
            appraisalChanged = appraisalChanged,
            emotionChanged = emotionChanged,
            motiveChanged = motiveChanged,
            conflictChanged = conflictChanged,
            commonGroundChanged = commonGroundChanged,
            privateResidue = privateResidue,
            fingerprint = fingerprint,
        )
    }

    private fun sameEmotion(previous: JSONObject?, next: JSONObject): Boolean {
        if (previous == null) return false
        val keys = listOf("feeling", "cause", "otherFeeling", "impulse", "restraint")
        return keys.all { normalize(previous.optString(it)) == normalize(next.optString(it)) } &&
            previous.optInt("strength", 2) == next.optInt("strength", 2)
    }

    private fun hasRealMotiveChange(changes: JSONArray): Boolean {
        for (i in 0 until changes.length()) {
            val item = changes.optJSONObject(i) ?: continue
            if (item.optString("op").lowercase() in setOf("start", "revise", "pause", "resume", "release")) return true
        }
        return false
    }

    private fun hasNovelThought(previous: JSONArray?, proposed: JSONArray): Boolean {
        val old = buildSet {
            if (previous != null) for (i in 0 until previous.length()) {
                previous.optJSONObject(i)?.optString("thought")?.let { add(normalize(it)) }
            }
        }
        for (i in 0 until proposed.length()) {
            val candidate = normalize(proposed.optJSONObject(i)?.optString("thought").orEmpty())
            if (candidate.isNotBlank() && candidate !in old) return true
        }
        return false
    }

    private fun motiveSignature(values: JSONArray?): String = buildList {
        if (values != null) for (i in 0 until values.length()) {
            val item = values.optJSONObject(i) ?: continue
            add(listOf(item.optString("op"), item.optString("id"), item.optString("aim"))
                .joinToString(":") { normalize(it) })
        }
    }.sorted().joinToString(",")

    private fun thoughtSignature(values: JSONArray?): String = buildList {
        if (values != null) for (i in 0 until values.length()) {
            values.optJSONObject(i)?.optString("thought")?.let { add(normalize(it)) }
        }
    }.sorted().joinToString(",")

    private fun socialSignature(value: JSONObject?): String {
        if (value == null) return ""
        return listOf(value.optString("targetId"), value.optString("interpretation"), value.optString("reason"))
            .joinToString(":") { normalize(it) }
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace(Regex("[\\s，。！？!?、；;：:“”‘’…~～—_\"'（）()]+"), "")
        .trim()
}
