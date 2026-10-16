package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Pure adapter: the only durable subjective goal collection is innerLife.motives. */
internal object CharacterGoalConvergence {
    fun relationships(source: JSONArray?): List<JSONObject> =
        (0 until (source?.length() ?: 0)).mapNotNull { source?.optJSONObject(it) }
            .filter { it.optString("kind") == "relationship" }

    /** Preserve the original id, provenance and attempt log on every update. */
    fun applyRelationship(
        old: JSONArray?, proposal: JSONObject, originId: String, persona: String,
        action: String = "silent", success: Boolean = false, verifiedReceiptId: String = "",
        summary: String = "", now: Instant = Instant.now(),
    ): JSONArray {
        val rows = (0 until (old?.length() ?: 0)).mapNotNull { old?.optJSONObject(it) }
            .map { JSONObject(it.toString()) }.toMutableList()
        fun result() = JSONArray().apply { rows.forEach(::put) }
        val status = proposal.optString("status").lowercase()
        if (status !in setOf("considering", "ready", "released")) return result()
        val askedId = proposal.optString("motiveId").trim()
        val aim = proposal.optString("aim").trim().take(180)
        val why = proposal.optString("why").trim().take(180)
        val existing = rows.firstOrNull {
            ((it.optString("kind") == "relationship" || it.optString("kind").isBlank()) &&
                ((askedId.isNotBlank() && it.optString("id") == askedId) ||
                    (askedId.isBlank() && aim.isNotBlank() &&
                        sameCharacterMotive(it.optString("aim"), aim))))
        }
        if (askedId.isNotBlank() && existing == null) return result()
        if (status == "released") {
            if (existing != null && existing.optString("status") !in setOf("released", "achieved"))
                existing.put("status", "released").put("closedAt", now.toString())
                    .put("closureEvidenceId", originId)
                    .put("closedBecause", proposal.optString("reason").ifBlank {
                        "本人已不想继续这一目标"
                    }.take(180))
            return result()
        }
        if (existing == null && (originId.isBlank() || aim.isBlank() || why.isBlank() ||
                rows.count { CharacterMotiveState.isActive(it) || it.optString("status") == "paused" } >= 6))
            return result()
        val target = existing ?: JSONObject().put("id", UUID.randomUUID().toString())
            .put("aim", aim).put("why", why).put("priority", 2)
            .put("createdAt", now.toString()).put("evidenceId", originId)
            .put("outcomes", JSONArray()).also(rows::add)
        if (target.optString("status") in setOf("achieved", "released")) return result()
        target.put("kind", "relationship").put("personaSignature", persona)
            .put("updatedAt", now.toString()).put("stage", status)
        // Existing motives may be renamed only by an explicit id-based revision.
        if (existing != null && askedId.isNotBlank() && aim.isNotBlank() && why.isNotBlank())
            target.put("aim", aim).put("why", why)
        val approach = proposal.optString("approach").take(180)
        if (approach.isNotBlank()) target.put("nextStep", approach)
        val isMatchingAttempt = action != "silent" && verifiedReceiptId.isNotBlank() &&
            proposal.optString("deliveryAction").lowercase() == action.lowercase()
        if (isMatchingAttempt) {
            val history = target.optJSONArray("outcomes") ?: JSONArray()
            if ((0 until history.length()).none {
                    history.optJSONObject(it)?.optString("id") == verifiedReceiptId }) {
                target.put("outcomes", JSONArray().apply {
                    for (i in maxOf(0, history.length() - 7) until history.length()) put(history.opt(i))
                    put(JSONObject().put("id", verifiedReceiptId).put("action", action)
                        .put("success", success).put("summary", summary.take(230))
                        .put("at", now.toString()))
                }).put("lastAttemptAt", now.toString())
                    .put("lastAttemptOutcome", if (success) "delivered_waiting_feedback" else "needs_review")
                if (success) target.put("status", "waiting_feedback")
                    .put("waitingFor", "等待真实反馈或新的可靠结果；行动成功不等于愿望达成")
                else target.put("status", "active")
            }
        } else if (target.optString("status").isBlank()) target.put("status", "active")
        return result()
    }
}
