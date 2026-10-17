package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/** A small projection inside the canonical inner-life state, never a second emotion engine. */
internal object CharacterActivityExpectation {
    val tools = setOf("read_book", "read_xiaohongshu", "play_solo_game", "digital_world_action", "write_journal")

    fun begin(previous: JSONArray?, id: String, tool: String, proposal: JSONObject?, now: Instant): JSONArray? {
        if (tool !in tools || id.isBlank() || proposal == null) return null
        val wanted = proposal.optString("wanted").trim().take(180)
        val why = proposal.optString("why").trim().take(180)
        if (wanted.isBlank() || why.isBlank()) return null
        val old = (0 until (previous?.length() ?: 0)).mapNotNull { previous?.optJSONObject(it) }
        // Retry cannot rewrite what the character hoped BEFORE the outcome.
        if (old.any { it.optString("id") == id }) return null
        return JSONArray().apply {
            old.takeLast(11).forEach(::put)
            put(JSONObject().put("id", id).put("tool", tool).put("wanted", wanted).put("why", why)
                .put("investment", proposal.optString("investment").trim().take(160))
                .put("status", "awaiting_result").put("at", now.toString()))
        }
    }

    fun resolve(records: JSONArray?, id: String, evidenceId: String, success: Boolean,
                result: String, now: Instant): Boolean {
        val entry = (0 until (records?.length() ?: 0)).mapNotNull { records?.optJSONObject(it) }
            .firstOrNull { it.optString("id") == id } ?: return false
        if (entry.optString("status") != "awaiting_result" || evidenceId.isBlank() || result.isBlank()) return false
        entry.put("status", "result_recorded").put("evidenceId", evidenceId)
            .put("success", success).put("result", result.take(450)).put("finishedAt", now.toString())
        return true
    }

    fun context(records: JSONArray?, now: Instant): String {
        val recent = (0 until (records?.length() ?: 0)).mapNotNull { records?.optJSONObject(it) }
            .filter { entry -> runCatching { Instant.parse(entry.optString("finishedAt").ifBlank { entry.optString("at") }) }
                .getOrNull()?.let { !it.isAfter(now) && Duration.between(it, now) <= Duration.ofDays(3) } == true }
            .takeLast(3)
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("【做事以前真实留下的期待与后来的结果｜自己的主观在意】")
            recent.forEach { entry ->
                appendLine("- ${entry.optString("tool")}；当时希望=${entry.optString("wanted")}；在意因为=${entry.optString("why")}；投入=${entry.optString("investment")}")
                if (entry.optString("status") == "result_recorded")
                    appendLine("  结果 evidenceId=${entry.optString("evidenceId")}；执行成功=${entry.optBoolean("success")}；实际=${entry.optString("result")}")
                else appendLine("  尚无结果，不能提前失望或庆祝；时间过去也不证明对方拒绝。")
            }
            append("比较自己的期待、投入和真实结果，再判断是否有感受变化；可以失落又欣赏、得意又不服气、好奇、安心，或没有明显情绪。工具成功只证明做过，不保证如愿；失败也不强制难过。感受沿用 innerLife.emotion/thoughts；想让用户听见什么再选择分享，别逐项汇报，别把旧结果当新刺激。")
        }.trim()
    }
}
