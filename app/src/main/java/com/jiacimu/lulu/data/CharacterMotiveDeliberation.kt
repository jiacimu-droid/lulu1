package com.jiacimu.lulu.data

import java.time.Instant
import org.json.JSONArray

/** Read-only comparison of personally owned wishes; not another goal store or action selector. */
internal object CharacterMotiveDeliberation {
    fun context(motives: JSONArray?, now: Instant = Instant.now()): String {
        val active = CharacterMotiveState.active(motives)
        val paused = CharacterMotiveState.paused(motives)
        if (active.isEmpty() && paused.isEmpty()) return ""
        return buildString {
            appendLine("【同时存在的个人愿望｜本人自主取舍】")
            active.take(6).forEach { m ->
                val due = runCatching { Instant.parse(m.optString("reviewAfter")) }.getOrNull()
                val stage = when {
                    m.optString("status") == "waiting_feedback" ->
                        "行动后还在等真实反馈，不能自认达成"
                    due != null && due.isAfter(now) ->
                        "约定未来再次考虑，尚未到复查时间"
                    m.optString("waitingFor").isNotBlank() ->
                        "在等：" + m.optString("waitingFor").take(110)
                    due != null -> "旧复查时刻已到，可以重新判断"
                    else -> "目前仍在意，但没有必须现在行动的命令"
                }
                appendLine("- motiveId=" + m.optString("id") + "；想做=" +
                    m.optString("aim").take(120) + "；状态=" + stage)
                val outcomes = m.optJSONArray("outcomes") ?: JSONArray()
                val last = outcomes.optJSONObject(outcomes.length() - 1)
                if (last != null) appendLine("  最近实际尝试：" +
                    (if (last.optBoolean("success")) "执行成功" else "未成功") +
                    "；" + last.optString("summary").take(140))
                m.optString("nextStep").takeIf(String::isNotBlank)?.let {
                    appendLine("  曾考虑的下一步：" + it.take(130))
                }
            }
            if (paused.isNotEmpty()) appendLine("暂缓而非完成：" +
                paused.take(3).joinToString("；") { it.optString("id") + "：" + it.optString("aim").take(80) } +
                "；没有新决定不可自动恢复。")
            append("多个愿望可以冲突，也可以暂时都不行动。考虑时机、感受、边界、执行条件、最近真实回执后，自己决定继续、换方案、改期、暂停或放下；不要机械按 priority 逐条执行。")
        }.trim()
    }
}
