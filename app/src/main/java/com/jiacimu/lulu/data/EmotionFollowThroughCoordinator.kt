package com.jiacimu.lulu.data


/**
 * Pure recognition of a meaningful relational disagreement. It never changes
 * emotion, schedules a second action or sends an extra model request.
 * The actual character decides whether anything matters in its existing turn.
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
    fun needsConcernAssessment(userText: String, appraisal: org.json.JSONObject?): Boolean {
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
        return appraisal?.optString("concernAssessment").isNullOrBlank()
    }

    // This is only a classification helper. It never authors a character's
    // feelings, creates a pending concern, or sends an automatic apology.
}
