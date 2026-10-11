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

    // This is only a classification helper. Actual deliberation belongs to
    // the original chat/phone/meeting decision. There is deliberately no
    // post-reply reflection request, no forced mood and no extra online wake.
}
