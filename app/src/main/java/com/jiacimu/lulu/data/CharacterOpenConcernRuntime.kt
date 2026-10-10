package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * The enduring part of a character's fictional deliberation. Unlike the full
 * thought timeline, this is a small set of revisable, unresolved *own* concerns.
 * None of these claims are objective statements about the user's intentions.
 *
 * The LLM may optionally open, revise or settle a concern based on a grounded
 * event. This store never manufactures anxiety, turns silence into a user fact,
 * or treats a proposed action as an executed action.
 */
internal object CharacterOpenConcernRuntime {
    private val lifetime = Duration.ofHours(48)
    private val acceptedStatuses = setOf("open", "revise", "settled", "dismissed")

    fun update(
        previous: JSONArray?, appraisal: JSONObject?, evidenceId: String,
        now: Instant = Instant.now(),
    ): JSONArray {
        val existing = recent(previous, now).toMutableList()
        val proposal = appraisal?.optJSONObject("pendingConcern") ?: return JSONArray().apply {
            existing.forEach(::put)
        }
        val status = proposal.optString("status", "open").trim().lowercase()
        if (status !in acceptedStatuses || evidenceId.isBlank()) return JSONArray().apply {
            existing.forEach(::put)
        }

        val requestedId = proposal.optString("threadId").trim().take(100)
        val match = existing.indexOfFirst { it.optString("id") == requestedId && requestedId.isNotBlank() }
        if (status == "settled" || status == "dismissed") {
            if (match >= 0) existing.removeAt(match)
            return JSONArray().apply { existing.forEach(::put) }
        }

        val focus = proposal.optString("focus").trim().take(180)
        val why = proposal.optString("whyItMatters").trim().take(180)
        // Deliberation is not mandatory just because the model emitted a field.
        // New threads need a concrete subjective question and a reason it matters.
        if (match < 0 && (focus.isBlank() || why.isBlank())) return JSONArray().apply {
            existing.forEach(::put)
        }
        val previousThread = existing.getOrNull(match)
        val id = previousThread?.optString("id")
            ?: "concern-${evidenceId.hashCode().toUInt().toString(16)}"
        val revised = JSONObject()
            .put("id", id)
            .put("focus", focus.ifBlank { previousThread?.optString("focus").orEmpty() })
            .put("whyItMatters", why.ifBlank { previousThread?.optString("whyItMatters").orEmpty() })
            .put("hesitation", proposal.optString("hesitation").trim().take(170))
            .put("possibleNextStep", proposal.optString("possibleNextStep").trim().take(170))
            .put("evidenceId", evidenceId)
            .put("createdAt", previousThread?.optString("createdAt").orEmpty().ifBlank { now.toString() })
            .put("updatedAt", now.toString())
        previousThread?.optJSONObject("lastOutcome")?.let { revised.put("lastOutcome", it) }
        if (match >= 0) existing.removeAt(match)
        existing.add(revised)
        return JSONArray().apply { existing.takeLast(4).forEach(::put) }
    }

    /** Store a real executor receipt, not the model's promise or hoped-for result.
     * A successful action doesn't automatically settle an emotional concern:
     * the character can still need time, or later realize the action was wrong.
     */
    fun recordOutcome(
        previous: JSONArray?, threadId: String, receiptId: String,
        action: String, success: Boolean, summary: String,
        now: Instant = Instant.now(),
    ): JSONArray {
        val items = recent(previous, now)
        return JSONArray().apply {
            items.forEach { original ->
                if (original.optString("id") != threadId || receiptId.isBlank()) {
                    put(original)
                } else {
                    val previousReceipt = original.optJSONObject("lastOutcome")
                    if (previousReceipt?.optString("id") == receiptId) {
                        put(original)
                    } else put(JSONObject(original.toString()).put("lastOutcome", JSONObject()
                        .put("id", receiptId.take(120)).put("action", action.take(70))
                        .put("success", success).put("summary", summary.take(230))
                        .put("at", now.toString())))
                }
            }
        }
    }

    fun recent(previous: JSONArray?, now: Instant = Instant.now()): List<JSONObject> =
        if (previous == null) emptyList() else (0 until previous.length())
            .mapNotNull(previous::optJSONObject)
            .filter { item ->
                val created = runCatching { Instant.parse(item.optString("createdAt")) }.getOrNull()
                created != null && !created.isAfter(now) &&
                    Duration.between(created, now) <= lifetime &&
                    item.optString("focus").isNotBlank()
            }.takeLast(4)

    /** One quiet, optional rethink after a newly changed concern; never a repeating anxiety loop. */
    fun meritsOneFollowThrough(previous: JSONArray?, now: Instant = Instant.now()): Boolean =
        recent(previous, now).any { item ->
            val updated = runCatching { Instant.parse(item.optString("updatedAt")) }.getOrNull()
            updated != null && !updated.isAfter(now) &&
                Duration.between(updated, now).toMinutes() in 0..60
        }

    fun context(previous: JSONArray?, now: Instant = Instant.now()): String {
        val threads = recent(previous, now)
        if (threads.isEmpty()) return ""
        return buildString {
            appendLine("【自己仍未想通的心事｜私人猜测，不是用户事实，也不是外部待办】")
            for (entry in threads) {
                val touched = runCatching { Instant.parse(entry.optString("updatedAt")) }.getOrNull()
                val elapsed = touched?.let { Duration.between(it, now).toMinutes().coerceAtLeast(0) }
                appendLine("- threadId=${entry.optString("id")}；在意=${entry.optString("focus")}" +
                    "；为什么在意=${entry.optString("whyItMatters")}" +
                    "；犹豫=${entry.optString("hesitation")}" +
                    "；曾考虑=${entry.optString("possibleNextStep")}" +
                    "；距上次考虑约${elapsed ?: "未知"}分钟")
                entry.optJSONObject("lastOutcome")?.let { actual ->
                    appendLine("  上次实际尝试：${actual.optString("action")}；" +
                        "${if (actual.optBoolean("success")) "执行成功" else "执行失败"}；" +
                        "${actual.optString("summary")}")
                }
            }
            appendLine("这些心事可能已改变。可以主动修正或放下，若无新依据也可以原样保留。" +
                "不用为了消除心事而必然联系用户；如果选了一个真正可做的事，必须由执行器确认结果。" +
                "不要同一轮重新创造内容相同的心事，也不要把曾经的担心当成用户确实出了事。")
        }.trim()
    }
}
