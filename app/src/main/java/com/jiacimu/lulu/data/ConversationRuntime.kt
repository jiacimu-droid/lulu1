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
    /** Only a model-authored decision may accept/reject shared understanding. */
    val groundingDecision: String = "",
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
    /**
     * The planner sees the full user turn and relationship history. No lexical
     * classifier may force a repair, override its chosen dialogueMove or
     * decide that a user accepted a previous guess.
     */
    fun plannerConstraint(userText: String): String = """
        【会话理解属于你本人】
        结合对方本轮完整表达与真实前文，自主判断对方是在纠正、确认、开玩笑、
        请求解释还是转移话题。发生纠正时，用你自己选择的 dialogueMove 表达理解，
        不机械枚举猜测或固定道歉；对方已经给出明确方向时可直接依新意思行动。
        不得把先前自己理解的版本当成对方最终意图；语义状态由本轮模型更新，
        程序不会从“不是”“对”等字眼替你确认或否定。
        如果涉及仍待确认的旧共同理解，可选 dialogueMove.commonGroundDecision=
        accept|reject|unchanged。只有真正确认或驳回了此前候选理解，才选 accept/reject。
    """.trimIndent()

    fun resolve(raw: JSONObject?, speechIntent: String, userText: String): DialogueMovePlan {
        val typeRaw = raw?.optString("type").orEmpty()
        val proposed = DialogueMoveType.fromWire(typeRaw)
        val candidate = raw?.optString("candidate").orEmpty().trim().take(220)
        val target = raw?.optString("target").orEmpty().trim().take(180)
        val confidence = raw?.optDouble("confidence", 0.0)?.takeIf { it.isFinite() }
            ?.coerceIn(0.0, 1.0) ?: 0.0
        val intent = raw?.optString("contentIntent").orEmpty().trim()
            .ifBlank { speechIntent.trim() }.take(500)

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
        // Bubble guidance is itself a model decision. No type-specific
        // program ceiling silently shortens a serious explanation.
        val requestedBubbles = raw?.optInt("maxBubbles", 2)?.coerceIn(1, 4) ?: 2
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
            maxBubbles = requestedBubbles,
            groundingDecision = raw?.optString("commonGroundDecision").orEmpty()
                .takeIf { it in setOf("accept", "reject") } ?: "",
        )
    }

    fun expressionConstraint(plan: DialogueMovePlan): String = buildString {
        appendLine("【角色模型已选择的会话动作】${plan.type.wire}")
        if (plan.target.isNotBlank()) appendLine("这次想回应的具体内容：${plan.target}")
        if (plan.contentIntent.isNotBlank()) appendLine("本人选择想传达的意思：${plan.contentIntent}")
        if (plan.repairFormat == RepairFormat.CANDIDATE && plan.candidate.isNotBlank()) {
            appendLine("本人想向对方确认的候选理解：${plan.candidate}")
            appendLine("这仍是待确认的理解，保留不确定性，依据对方实际回应再修订。")
        }
        appendLine("把这个交流意图放回两人的真实关系，用本人的语言与节奏实现。本人可以坦率、含蓄、热烈、克制或平淡，具体态度承接当前设定与感受。气泡按完整意群自然划分，展开程度由本人结合当前话题和必要答案决定。")
        if (plan.isRepair) appendLine("承接对方已说出的澄清，修正需要修正的那一处；真实仍不明白时，可以用自己的方式确认。共同理解恢复后，继续怎样聊由本人结合对方回应选择。")
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
    /** Previously, a regex would mutate shared understanding BEFORE the role
     * was allowed to interpret the new user turn. That path is removed.
     */
    fun afterDecision(
        characterId: String,
        conversationKey: String,
        evidenceId: String,
        plan: DialogueMovePlan,
    ) {
        when (plan.groundingDecision) {
            "accept" -> CharacterInnerLifeStore.acceptLatestGroundingCandidate(
                characterId = characterId,
                conversationKey = conversationKey,
                evidenceId = evidenceId,
            )
            "reject" -> CharacterInnerLifeStore.rejectGroundingCandidates(
                characterId = characterId,
                conversationKey = conversationKey,
                evidenceId = evidenceId,
                reason = "角色模型确认用户驳回了先前候选理解",
            )
        }
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
        hasChosenToolAction: Boolean = false,
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
        val appraisalChanged = appraisal != null && (
            listOf("meaning", "responseAim", "uncertainty", "interactionMove", "tension")
                .any { key ->
                    val next = appraisal.optString(key).trim()
                    next.isNotBlank() && normalize(next) != normalize(previousAppraisal?.optString(key).orEmpty())
                } || (alternativeInterpretations(appraisal).isNotBlank() &&
                    alternativeInterpretations(appraisal) != alternativeInterpretations(previousAppraisal)) ||
                    (privateIdeaSignature(appraisal).isNotBlank() &&
                    privateIdeaSignature(appraisal) != privateIdeaSignature(previousAppraisal))
            )

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
                emotionChanged || motiveChanged || conflictChanged ||
                // An actually new conversational focus can change the
                // character's unspoken interpretation without changing their
                // mood or adding a permanent motive. Require an authored
                // causal change as well: fresh input alone is insufficient.
                (focusChanged && (appraisalChanged || hasChosenToolAction) &&
                    basis?.optString("change").orEmpty().isNotBlank())
            )

        val fingerprintMaterial = buildString {
            append("focus=").append(normalize(focus)).append('|')
            append("meaning=").append(normalize(appraisal?.optString("meaning").orEmpty())).append('|')
            append("tension=").append(normalize(appraisal?.optString("tension").orEmpty())).append('|')
            append("possibilities=").append(alternativeInterpretations(appraisal)).append('|')
            append("privateIdeas=").append(privateIdeaSignature(appraisal)).append('|')
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

    /** Short simultaneous wishes are psychological changes, not actions. */
    private fun privateIdeaSignature(appraisal: JSONObject?): String {
        val ideas = appraisal?.optJSONArray("privateIdeas") ?: return ""
        return (0 until ideas.length()).map { normalize(ideas.optString(it)) }
            .filter(String::isNotBlank).distinct().sorted().joinToString("|")
    }

    private fun alternativeInterpretations(appraisal: JSONObject?): String {
        val readings = appraisal?.optJSONArray("possibleReadings") ?: return ""
        return (0 until minOf(readings.length(), 3))
            .map { normalize(readings.optString(it)) }
            .filter(String::isNotBlank).sorted().joinToString("|")
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
