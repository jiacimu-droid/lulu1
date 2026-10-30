package com.jiacimu.lulu.data

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Read-only diagnostics over the SAME private decisions and motive records used
 * by the actual role loop. Nothing here claims execution, changes state or
 * schedules an alarm. Kept out of normal chat prompts and model-call budgets.
 */
internal object CharacterLifeDiagnostics {
    fun lines(state: JSONObject, now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault()): List<String> = buildList {
        val motives = CharacterMotiveState.active(state.optJSONArray("motives"))
        if (motives.isNotEmpty()) {
            val waiting = motives.count { it.optString("status") == "waiting_feedback" }
            add("持续目标 · ${motives.size} 项；其中等待真实效果 ${waiting} 项")
            CharacterMotiveReview.pending(state).firstOrNull()?.let { next ->
                val at = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(next.dueAt)
                val kind = if (next.fallback) "普通感知复查机会" else "角色自行选定的复查时刻"
                add("下一次考虑 · $at（$kind，实际是否执行以调度与运行回执为准）")
            }
        }
        val decisions = state.optJSONArray("decisions")
        val recent = (0 until (decisions?.length() ?: 0))
            .mapNotNull { decisions?.optJSONObject(it) }
            .filter {
                val at = runCatching { Instant.parse(it.optString("at")) }.getOrNull()
                at != null && !at.isAfter(now)
            }
            .maxByOrNull { it.optString("at") }
        if (recent != null) {
            val selected = recent.optString("selected").ifBlank { "未标注" }
            val execution = when {
                recent.optString("executionState") == "pending" ->
                    "已有决定，执行总结尚未回写"
                !recent.has("executionState") -> "旧记录未提供完整执行阶段"
                selected == "silent" -> "角色选择安静；不代表目标完成"
                recent.optBoolean("succeeded") -> "执行器已回写成功总结；目标效果仍待单独核对"
                else -> "执行器已回写未完成/失败总结；不可冒充成功"
            }
            val decidedAt = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone)
                .format(Instant.parse(recent.optString("at")))
            add("最近决定 · $decidedAt · $selected；$execution")
            recent.optString("outcome").takeIf(String::isNotBlank)?.let {
                add("执行摘要 · ${it.take(150)}（具体工具步骤以原始回执为准）")
            }
        }
    }
}
