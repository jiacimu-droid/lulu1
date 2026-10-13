package com.jiacimu.lulu.data

import org.json.JSONObject

/** A persistent wish is a choice input, never an automatic action trigger. */
internal object CharacterIntentionContinuation {
    fun cue(goal: JSONObject?): String? {
        if (goal == null || goal.optString("stage") in setOf("paused", "released", "executing")) return null
        val id = goal.optString("createdAt")
        val aim = goal.optString("aim")
        if (aim.isBlank()) return null
        return buildString {
            append("持续愿望${if (id.isNotBlank()) " intentionId=$id" else "（本轮新想法）"}：$aim；自己的缘由=${goal.optString("motive")}；阶段=${goal.optString("stage", "considering")}")
            val next = goal.optString("nextStep")
            if (next.isNotBlank()) append("；之前考虑的下一步=$next（需要结合眼前重新选择，不是已执行）")
            else append("；还没有选好具体下一步，请判断现在能自然做的一件小事，或明确暂缓/调整/放下，不反复只展示这个愿望")
        }
    }
}
