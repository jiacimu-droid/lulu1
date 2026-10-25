package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** One read-only view of a motive plan; authoritative state remains in innerLife.motives. */
internal object CharacterMotiveProgress {
    private val planFields = listOf("nextStep", "waitingFor", "reviewAfter", "successCondition", "stopCondition", "completionKind")

    fun applyPlan(target: JSONObject, proposal: JSONObject, evidenceId: String, now: Instant) {
        var changed = false
        for (key in planFields) {
            if (!proposal.has(key)) continue
            val value = proposal.optString(key).trim()
            val clean = when (key) {
                "reviewAfter" -> value.takeIf {
                    it.isBlank() || runCatching { !Instant.parse(it).isBefore(now) }.getOrDefault(false)
                }
                "nextStep", "successCondition", "stopCondition" -> value.take(180)
                "completionKind" -> value.takeIf { it in setOf("", "effect", "execution") }
                else -> value.take(120)
            } ?: continue
            if (target.optString(key) != clean) {
                target.put(key, clean)
                changed = true
            }
        }
        if (changed) {
            target.put("planEvidenceId", evidenceId.take(220))
            // Revising a next step does not silently book the old past clock
            // time again. The new plan falls back to ordinary perception unless
            // the role actually chose another future review time.
            for (key in listOf("reviewAfter", "fallbackReviewAt")) {
                val at = runCatching { Instant.parse(target.optString(key)) }.getOrNull()
                if (at != null && !at.isAfter(now)) target.remove(key)
            }
        }
    }

    /** Waiting never orders a tool call. New evidence can still change the plan. */
    fun isDue(motive: JSONObject, now: Instant = Instant.now()): Boolean {
        val time = motive.optString("reviewAfter").trim()
        if (time.isNotBlank()) {
            val parsed = runCatching { Instant.parse(time) }.getOrNull() ?: return false
            if (now.isBefore(parsed)) return false
        }
        return time.isNotBlank() || motive.optString("waitingFor").isBlank()
    }

    fun brief(motive: JSONObject): String = buildList {
        motive.optString("successCondition").trim().takeIf(String::isNotBlank)?.let { add("完成依据：$it") }
        motive.optString("stopCondition").trim().takeIf(String::isNotBlank)?.let { add("停止/暂缓条件：$it") }
        motive.optString("nextStep").trim().takeIf(String::isNotBlank)?.let { add("拟议下一步：$it") }
        motive.optString("waitingFor").trim().takeIf(String::isNotBlank)?.let { add("正在等待：$it") }
        motive.optString("reviewAfter").trim().takeIf(String::isNotBlank)?.let { add("重新考虑时间：$it") }
    }.joinToString("；")

    /** A recovery opportunity at the existing perception cadence, not an authored appointment. */
    fun ensureFollowUp(motive: JSONObject, fallbackAt: Instant, now: Instant) {
        if (!CharacterMotiveState.isActive(motive)) return
        val explicit = runCatching { Instant.parse(motive.optString("reviewAfter")) }.getOrNull()
        if (explicit != null && explicit.isAfter(now)) return
        val existing = runCatching { Instant.parse(motive.optString("fallbackReviewAt")) }.getOrNull()
        if (existing == null)
            motive.put("fallbackReviewAt", fallbackAt.toString())
    }

    /** No fuzzy match: explicit active ID, or an unambiguous same-event creation ref. */
    fun resolveMotiveId(motives: JSONArray?, proposedId: String, proposedRef: String,
                        evidenceId: String): String {
        val active = CharacterMotiveState.active(motives)
        val id = proposedId.trim()
        if (id.isNotBlank()) return active.firstOrNull { it.optString("id") == id }
            ?.optString("id").orEmpty()
        val ref = proposedRef.trim()
        if (ref.isBlank() || evidenceId.isBlank()) return ""
        val matches = active.filter { it.optString("creationRef") == ref &&
            it.optString("evidenceId") == evidenceId }
        return if (matches.size == 1) matches.single().optString("id") else ""
    }

    /** Revoked evidence cannot continue to supply a revised plan. */
    fun invalidatePlanIfSourceDeleted(motive: JSONObject, eventId: String): Boolean {
        if (motive.optString("planEvidenceId") != eventId) return false
        planFields.forEach { motive.remove(it) }
        motive.remove("fallbackReviewAt")
        motive.remove("planEvidenceId")
        return true
    }
}
