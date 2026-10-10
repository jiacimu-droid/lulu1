package com.jiacimu.lulu.data

import org.json.JSONArray
import java.time.Duration
import java.time.Instant

/**
 * The feedback half of the personality loop. The executor's *actual* receipt,
 * not the model's hoped-for action, is what future autonomous turns can use.
 */
internal object CharacterActionFeedback {
    fun recent(decisions: JSONArray?, now: Instant = Instant.now(), limit: Int = 3): String {
        if (decisions == null || decisions.length() == 0) return ""
        val entries = (0 until decisions.length())
            .mapNotNull(decisions::optJSONObject)
            .filter { entry ->
                val at = runCatching { Instant.parse(entry.optString("at")) }.getOrNull()
                at != null && !at.isAfter(now) && Duration.between(at, now) <= Duration.ofDays(2)
            }
            .takeLast(limit.coerceIn(1, 5))
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("【最近真实决策与执行回执｜下一次行动必须参考】")
            for (entry in entries) {
                val choice = entry.optString("selected")
                val succeeded = entry.optBoolean("succeeded")
                val outcome = entry.optString("outcome")
                val verdict = when {
                    choice == "silent" -> "选择安静（没有执行外部动作）"
                    succeeded -> "执行成功"
                    else -> "执行失败或未完成"
                }
                append("- 选择=").append(choice.take(45))
                    .append("；结果=").append(verdict)
                    .append("；原因=").append(entry.optString("reason").take(100))
                    .append("；回执=").append(outcome.take(160))
                entry.optString("causalEvidenceId").takeIf(String::isNotBlank)?.let {
                    append("；原始线索ID=").append(it.take(110))
                }
                appendLine()
            }
            append("发生过的行动成功才可当作经历；失败要考虑修改方案或暂停，不能反复假称已执行。选择安静也可以，但不是证明自己没有感受。")
        }
    }
}
