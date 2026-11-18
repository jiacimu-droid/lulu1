package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.time.Instant

/**
 * Bounded completion of an omitted private assessment in an existing turn.
 * The same model owns its private choices. This path cannot author an outward action.
 */
internal object EmotionFollowThroughCoordinator {
    private val remorse = listOf("很后悔", "后悔", "愧疚", "懊悔", "我错了", "是我不对", "对不起",
        "抱歉", "我没做到", "我不该", "是我失约", "让我想想怎么补救")
    private val friction = listOf("吵架", "生气", "伤心", "难过", "委屈", "不高兴", "不舒服",
        "你没", "你没有", "你答应", "失约", "我不想", "你为什么", "我很烦",
        "骗我", "不信任", "为什么这样", "让我失望", "怎么能", "伤害我", "不在乎", "冷暴力", "辜负")

    private val repair = listOf("抱抱", "我在", "我听你说", "听你说完", "陪着你", "别生气",
        "不想让你难过", "慢慢说", "让我哄", "我会改", "下次我会", "放心", "别哭")

    fun qualifies(roleText: String, recentUserText: String): Boolean =
        // Important personal boundaries, being diminished and lack of safety
        // matter even when an AI does not apologize using a specific keyword.
        CharacterGrowthTrajectory.feedbackTopic(recentUserText) != null ||
            (friction.any(recentUserText::contains) &&
                (remorse.any(roleText::contains) || repair.any(roleText::contains)))

    /**
     * Evidenced, consequential relationship feedback merits checking whether
     * the character consciously chose to keep or let go of a concern.
     * This is NOT a rule to create one, nor a claim about the user's intentions.
     */
    fun needsConcernAssessment(userText: String, appraisal: JSONObject?): Boolean {
        val explicitImpact = listOf(
            "你让我生气", "我很生气", "我真的生气", "我生气了", "让我失望",
            "你让我失望", "你伤害", "伤害到我", "很委屈", "没有安全感",
            "不尊重我", "轻视我", "你骗我", "冷暴力", "我很难过",
            "我不开心", "让我难过", "你没把我当回事", "我们吵架",
        ).any(userText::contains)
        if (!explicitImpact || userText.length > 4_000) return false
        if (CharacterOpenConcernRuntime.hasProposals(appraisal)) return false
        // Even 'there is no enduring concern, because ...' is an actual
        // deliberated decision. A missing field is not.
        return (appraisal?.opt("concernAssessment") as? String).isNullOrBlank()
    }

    data class Assessment(val appraisal: JSONObject?, val innerLife: JSONObject?)

    suspend fun completeOmittedAssessment(
        characterId: String,
        userText: String,
        history: String,
        sceneContext: String,
        originalDecision: String,
        title: String,
        connection: ModelConnection,
        appraisal: JSONObject?,
        innerLife: JSONObject?,
        now: Instant,
    ): Assessment? {
        if (!needsConcernAssessment(userText, appraisal)) return null
        // Optional private state must never indefinitely hold up the chosen reply.
        val review = withTimeoutOrNull(20_000L) {
            LuluAiServices.gateway.generate(
                characterId = characterId,
                facts = buildString {
                    appendLine("【现实互动】${userText.takeLast(2_000)}")
                    if (history.isNotBlank()) appendLine("【已发生的上下文】${history.takeLast(3_000)}")
                    appendLine("【原本的这轮角色判断】${originalDecision.take(2_000)}")
                    appendLine("【已有的私人心事】${CharacterOpenConcernRuntime.contextForRole(
                        characterId, CharacterInnerLifeStore.snapshot(characterId)
                            .optJSONArray("openConcerns"), now
                    )}")
                },
                instruction = """
                    你仍是同一个角色，只补齐刚才同一轮的内部判断。
                    对方明确表达了关系中实际的不愉快，但原决策漏掉了一个问题：
                    你自己是否还有未解决的事放在心上？这不是必须哄、必须原谅或必须追着对方联系的任务。
                    从自己的性格、真实经历、情绪与相互矛盾的想法出发，仔细判断。
                    如果有一件或数件真正不同主题的持续心事，在 appraisal.pendingConcerns 中分别写出
                    topicKey、status=open/revise、threadId(续想时)、focus、whyItMatters、hesitation、
                    possibleNextStep、priority(1到3)，最多四个不同主题，不能对同一问题换句话重复建档。
                    如果确实不想持续关注，给 appraisal.concernAssessment 写明自己不继续挂心的具体原因；
                    如果决定持续关注，也写这个选择的简短原因；不能凭空认定用户已经原谅。
                    可选 innerLife.thoughts 或 emotion 表示真正有变化的主观状态。若本人想持续推进且尚无同主题动机，可用既有 innerLife.motives 协议保存本人目标，有明确复查时间才填 reviewAfter。
                    只返回紧凑 JSON：{"appraisal":{"concernAssessment":"自己的决定与理由",
                    "pendingConcerns":[]},"innerLife":{}}。
                    没有要保存的心事就使用空数组；不执行工具、不发送新消息、不修改旧事实。
                """.trimIndent(),
                source = "聊天重大关系反馈·原决策复核",
                title = title,
                maxTokens = 1_200,
                connectionOverride = connection,
                memoryRequest = UnifiedMemoryRequest(
                    currentInput = userText,
                    sceneContext = sceneContext,
                    recentContext = history,
                    taskIntent = "同一轮决策补齐未解决心事的判断，不额外生成对外行动",
                ),
            ).getOrElse { error ->
                if (error is CancellationException) throw error
                return@withTimeoutOrNull null
            }.text.let(ModelStructuredOutput::objectOrNull)
        } ?: return null
        return mergeAssessment(appraisal, innerLife, review)
    }

    internal fun mergeAssessment(
        appraisal: JSONObject?, innerLife: JSONObject?, review: JSONObject,
    ): Assessment? {
        val addendum = review.optJSONObject("appraisal")
        val reason = (addendum?.opt("concernAssessment") as? String)?.trim().orEmpty()
        if (reason.isBlank() && !CharacterOpenConcernRuntime.hasProposals(addendum)) return null
        val revised = JSONObject(appraisal?.toString() ?: "{}")
        if (reason.isNotBlank()) revised.put("concernAssessment", reason.take(240))
        if (addendum != null) {
            if (!CharacterOpenConcernRuntime.hasProposals(revised)) {
                addendum.optJSONArray("pendingConcerns")?.let { revised.put("pendingConcerns", it) }
                addendum.optJSONObject("pendingConcern")?.let { revised.put("pendingConcern", it) }
            }
            for (key in listOf("possibleReadings", "tension", "uncertainty")) {
                if (!revised.has(key) && addendum.has(key)) revised.put(key, addendum.opt(key))
            }
        }
        val extraLife = review.optJSONObject("innerLife")
        val sharedLife = JSONObject(innerLife?.toString() ?: "{}")
        if (extraLife != null) {
            for (key in listOf("emotion", "thoughts", "motives", "selfCorrection", "social")) {
                if (!sharedLife.has(key) && extraLife.has(key)) sharedLife.put(key, extraLife.opt(key))
            }
        }
        return Assessment(revised.takeIf { it.length() > 0 }, sharedLife.takeIf { it.length() > 0 })
    }
}
