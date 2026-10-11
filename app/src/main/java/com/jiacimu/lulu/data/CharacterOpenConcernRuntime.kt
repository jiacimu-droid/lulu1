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
    private val activeWindow = Duration.ofHours(48)
    private val acceptedStatuses = setOf("open", "revise", "settled", "dismissed")

    fun update(
        previous: JSONArray?, appraisal: JSONObject?, evidenceId: String,
        now: Instant = Instant.now(),
    ): JSONArray {
        // An unresolved question may fall quiet without having been solved. Keep the
        // thread, but stop treating old worries as live impulses after 48 hours.
        val existing = retained(previous).toMutableList()
        fun result() = JSONArray().apply { existing.takeLast(4).forEach(::put) }
        val proposal = appraisal?.optJSONObject("pendingConcern") ?: return result()
        val status = proposal.optString("status", "open").trim().lowercase()
        if (status !in acceptedStatuses || evidenceId.isBlank()) return result()
        val requestedId = proposal.optString("threadId").trim().take(100)
        val match = existing.indexOfFirst { requestedId.isNotBlank() && it.optString("id") == requestedId }
        if (status in setOf("settled", "dismissed")) {
            if (match >= 0) existing.removeAt(match)
            return result()
        }
        // Revisions must identify a real prior thread. Otherwise a malformed
        // revision could create duplicate "worries" from an imagined history.
        if (match < 0 && status != "open") return result()
        val prior = existing.getOrNull(match)
        val focus = proposal.optString("focus").trim().take(180)
            .ifBlank { prior?.optString("focus").orEmpty() }
        val why = proposal.optString("whyItMatters").trim().take(180)
            .ifBlank { prior?.optString("whyItMatters").orEmpty() }
        if (focus.isBlank() || why.isBlank()) return result()
        val hesitation = if (proposal.has("hesitation")) proposal.optString("hesitation").trim().take(170)
            else prior?.optString("hesitation").orEmpty()
        val nextStep = if (proposal.has("possibleNextStep")) proposal.optString("possibleNextStep").trim().take(170)
            else prior?.optString("possibleNextStep").orEmpty()
        if (prior != null) {
            val unchanged = focus == prior.optString("focus") && why == prior.optString("whyItMatters") &&
                hesitation == prior.optString("hesitation") && nextStep == prior.optString("possibleNextStep")
            val sameEvidence = prior.optString("evidenceId") == evidenceId ||
                (0 until (prior.optJSONArray("journey")?.length() ?: 0)).any { i ->
                    prior.optJSONArray("journey")?.optJSONObject(i)?.optString("evidenceId") == evidenceId
                }
            if (unchanged || sameEvidence) return result()
        }
        val id = prior?.optString("id") ?: "concern-${evidenceId.hashCode().toUInt().toString(16)}"
        val journey = prior?.optJSONArray("journey") ?: JSONArray()
        val step = JSONObject().put("evidenceId", evidenceId)
            .put("at", now.toString()).put("phase", if (prior == null) "产生" else "转变")
            .put("focus", focus).put("whyItMatters", why)
            .put("hesitation", hesitation).put("possibleNextStep", nextStep)
        val revised = JSONObject()
            .put("id", id).put("focus", focus).put("whyItMatters", why)
            .put("hesitation", hesitation).put("possibleNextStep", nextStep)
            .put("evidenceId", evidenceId)
            .put("createdAt", prior?.optString("createdAt").orEmpty().ifBlank { now.toString() })
            .put("updatedAt", now.toString())
            .put("journey", JSONArray().apply {
                for (i in maxOf(0, journey.length() - 4) until journey.length()) put(journey.opt(i))
                put(step)
            })
        prior?.optJSONObject("lastOutcome")?.let { revised.put("lastOutcome", it) }
        if (match >= 0) existing.removeAt(match)
        existing.add(revised)
        return result()
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
        val items = retained(previous)
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

    /** All unresolved threads survive idle time. Bounded count and revisions prevent
     * unbounded storage; old threads are *dormant*, never permanent traits.
     */
    private fun retained(previous: JSONArray?): List<JSONObject> =
        if (previous == null) emptyList() else (0 until previous.length())
            .mapNotNull(previous::optJSONObject)
            .filter { it.optString("id").isNotBlank() && it.optString("focus").isNotBlank() }
            .takeLast(4)

    fun recent(previous: JSONArray?, now: Instant = Instant.now()): List<JSONObject> =
        retained(previous).filter { item ->
            val updated = runCatching { Instant.parse(item.optString("updatedAt")) }.getOrNull()
            updated != null && !updated.isAfter(now) &&
                Duration.between(updated, now) <= activeWindow
        }

    /** A newly changed concern can warrant a single optional re-evaluation.
     * An idle/dormant concern cannot keep prompting autonomous check-ins.
     */
    fun meritsOneFollowThrough(previous: JSONArray?, now: Instant = Instant.now()): Boolean =
        recent(previous, now).any { item ->
            val updated = runCatching { Instant.parse(item.optString("updatedAt")) }.getOrNull()
            updated != null && Duration.between(updated, now).toMinutes() in 0..60
        }

    /** Preserve the *subjective* ending of a thought process when the role lets it go.
     * Closure is not proof that the other person's intent was ever correctly guessed.
     */
    fun closure(previous: JSONArray?, appraisal: JSONObject?, evidenceId: String,
                now: Instant = Instant.now()): JSONObject? {
        val proposal = appraisal?.optJSONObject("pendingConcern") ?: return null
        val status = proposal.optString("status").lowercase().trim()
        if (status !in setOf("settled", "dismissed") || evidenceId.isBlank()) return null
        val id = proposal.optString("threadId").trim()
        val prior = retained(previous).firstOrNull { it.optString("id") == id } ?: return null
        return JSONObject(prior.toString())
            .put("closureEvidenceId", evidenceId)
            .put("closedAt", now.toString())
            .put("resolution", proposal.optString("resolution").trim().take(200))
            .put("closedAs", status)
    }

    /** Deleting any source in the psychological journey invalidates the dependent
     * private conclusion; a deleted message must never survive as a "lesson".
     */
    fun dependsOnEvidence(entry: JSONObject, eventId: String): Boolean {
        if (eventId.isBlank()) return false
        fun matches(id: String) = id == eventId || id.contains(":$eventId:")
        if (matches(entry.optString("evidenceId")) ||
            matches(entry.optString("closureEvidenceId"))) return true
        val journey = entry.optJSONArray("journey") ?: return false
        return (0 until journey.length()).any { i ->
            matches(journey.optJSONObject(i)?.optString("evidenceId").orEmpty())
        }
    }

    fun closureContext(closed: JSONArray?, now: Instant = Instant.now(),
                       compact: Boolean = false): String {
        if (closed == null) return ""
        val horizon = Duration.ofDays(if (compact) 3 else 30)
        val recent = (0 until closed.length()).mapNotNull(closed::optJSONObject).filter {
            val at = runCatching { Instant.parse(it.optString("closedAt")) }.getOrNull()
            at != null && !at.isAfter(now) && Duration.between(at, now) <= horizon
        }.takeLast(if (compact) 1 else 2)
        if (recent.isEmpty()) return ""
        return buildString {
            appendLine("【已走过的心路转折｜过去的私人理解，不等于客观事实】")
            recent.forEach { item ->
                appendLine("· 曾在意：${item.optString("focus").take(120)}；" +
                    "最后${if (item.optString("closedAs") == "settled") "想通了" else "决定放下"}；" +
                    "缘由：${item.optString("resolution").ifBlank { "没有留下明确解释" }.take(170)}")
            }
            appendLine("这段过程已经结束；不要把旧情绪重新当成当前状态，除非有新的真实刺激。")
        }.trim()
    }

    fun context(previous: JSONArray?, now: Instant = Instant.now(),
                compact: Boolean = true): String {
        val active = recent(previous, now)
        val dormant = retained(previous).filterNot { item ->
            active.any { it.optString("id") == item.optString("id") }
        }
        if (active.isEmpty() && dormant.isEmpty()) return ""
        return buildString {
            if (active.isNotEmpty()) {
                appendLine("【自己仍未想通的心事｜私人猜测，不是用户事实，也不是外部待办】")
                active.takeLast(3).forEach { entry ->
                    val at = runCatching { Instant.parse(entry.optString("updatedAt")) }.getOrNull()
                    val elapsed = at?.let { Duration.between(it, now).toMinutes().coerceAtLeast(0) }
                    appendLine("- threadId=${entry.optString("id")}；在意=${entry.optString("focus")}" +
                        "；为什么在意=${entry.optString("whyItMatters")}" +
                        "；犹豫=${entry.optString("hesitation")}" +
                        "；曾考虑=${entry.optString("possibleNextStep")}" +
                        "；距上次转变约${elapsed ?: "未知"}分钟")
                    entry.optJSONObject("lastOutcome")?.let { actual ->
                        appendLine("  上次实际尝试：${actual.optString("action")}；" +
                            "${if (actual.optBoolean("success")) "执行成功" else "执行失败"}；" +
                            actual.optString("summary"))
                    }
                    if (!compact) {
                        val journey = entry.optJSONArray("journey") ?: JSONArray()
                        for (i in maxOf(0, journey.length() - 3) until journey.length()) {
                            val step = journey.optJSONObject(i) ?: continue
                            appendLine("  心路${step.optString("phase")}：${step.optString("focus").take(100)}；" +
                                "顾虑=${step.optString("hesitation").take(100)}；" +
                                "事实来源=${step.optString("evidenceId").take(110)}")
                        }
                    }
                }
                appendLine("可以改变看法、采取行动或放下；不必为了结束心事去打扰用户。")
            }
            if (dormant.isNotEmpty()) {
                appendLine("【暂时搁置的旧心事】共${dormant.size}件，超过48小时无新心理变化。" +
                    "它们不是此刻的情绪、不是人物性格，更不是催促行动的理由。")
                if (!compact) {
                    val entry = dormant.last()
                    appendLine("· 旧线索 threadId=${entry.optString("id")}：${entry.optString("focus").take(130)}。" +
                        "只有新事实真的与之相关时才重新判断、修订或放下，不默认它还困扰着自己。")
                }
            }
        }.trim()
    }

    
}
