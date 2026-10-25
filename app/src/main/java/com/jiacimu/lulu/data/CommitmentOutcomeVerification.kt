package com.jiacimu.lulu.data

import org.json.JSONObject

/**
 * Shared end-state gate for a role's commitments. The role supplies a reasoned
 * judgement and real source IDs; the executor checks provenance, chronology,
 * status and evidence kind. A recorded sentence proves speech, not reality.
 */
internal object CommitmentOutcomeVerification {
    data class Result(val accepted: Boolean, val reason: String, val evidenceIds: List<String> = emptyList())

    internal fun evaluate(
        task: CommitmentTask,
        ids: List<String>,
        explanation: String,
        events: List<SharedTimelineEvent>,
    ): Result {
        val requested = ids.map(String::trim).filter(String::isNotBlank).distinct()
        if (requested.isEmpty()) return Result(false, "未提供核实用的真实事件ID")
        if (explanation.trim().length < 8) return Result(false, "没有解释证据如何满足完成条件")
        val resolved = events.associateBy(SharedTimelineEvent::id)
        if (requested.any { id -> resolved[id] == null }) return Result(false, "至少一条引用证据不存在或已经撤回")
        val witnesses = requested.mapNotNull(resolved::get)
        if (witnesses.any { it.characterId != task.characterId ||
                it.occurredAt.isBefore(task.createdAt.minusSeconds(3)) })
            return Result(false, "证据不属于当前角色或早于原约定")
        val credible = witnesses.any { e ->
            when (task.verificationMode) {
                "subjective_report" -> e.evidenceKind == EventEvidenceKind.UserStatement ||
                    isObservedOutcome(e)
                "delivery" -> isExecutedAction(e)
                // An executable operation proves only that operation happened.
                // A successful alarm, message, call, or screenshot request does
                // not independently establish a separate real-world outcome.
                else -> isObservedOutcome(e)
            }
        }
        if (!credible) return Result(false, "尚无对应类型的可靠证据：行动成功只证明行动已执行，不能替代目标结果；声明与推测也不能单独结案")
        return Result(true, "已核对来源和时序，角色仍须对证据相关性负责", requested)
    }

    private fun isExecutedAction(e: SharedTimelineEvent): Boolean {
        if (e.evidenceKind != EventEvidenceKind.ToolResult || e.source != "tool-router") return false
        val wrapper = runCatching { JSONObject(e.content) }.getOrNull() ?: return false
        if (wrapper.optString("status") in setOf("pending", "running", "failed", "waiting_user",
                "waiting_permission", "requested", "denied")) return false
        val result = runCatching { JSONObject(wrapper.optString("result")) }.getOrNull() ?: return false
        return result.optBoolean("success", false)
    }

    private fun isObservedOutcome(e: SharedTimelineEvent): Boolean {
        // A scheduled check supplies cues for a new choice, never a decisive
        // verdict. Observations from scenes or generated prose aren't reality.
        if (e.evidenceKind != EventEvidenceKind.Observation ||
            e.source in setOf("commitment-follow-up", "model", "character-inference") ||
            e.channel.contains("剧场") || e.channel.contains("演绎") ||
            e.channel.contains("梦境")) return false
        return true
    }
}
