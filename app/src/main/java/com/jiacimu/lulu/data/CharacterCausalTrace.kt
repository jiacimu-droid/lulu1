package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A read-only explanation of one existing event. No new character state or model call.
 * Missing links are explicitly unknown, never interpreted as a deliberate refusal.
 */
internal data class CharacterCausalTraceLine(
    val title: String,
    val detail: String,
    val evidenceId: String = "",
)

internal object CharacterCausalTrace {
    private fun objectValue(value: Any?): JSONObject? = when (value) {
        is JSONObject -> value
        is String -> runCatching { JSONObject(value) }.getOrNull()
        else -> null
    }

    private fun receiptStatus(payload: JSONObject): String {
        val result = objectValue(payload.opt("result"))
        val status = payload.optString("status").lowercase()
        val innerStatus = result?.optString("status")?.lowercase().orEmpty()
        return when {
            payload.optString("stage") == "rejected" && status == "waiting_user" -> "等待主人授权"
            payload.optString("stage") == "rejected" -> "执行前被拒绝"
            status in setOf("waiting_user", "waiting_permission", "denied") -> "等待授权或确认"
            status in setOf("running", "pending", "requested", "requested_unverified") ||
                innerStatus in setOf("running", "pending", "requested", "requested_unverified") ->
                "结果尚未证实"
            status in setOf("failed", "error", "cancelled") || result?.optBoolean("success") == false ->
                "执行失败"
            status in setOf("succeeded", "verified", "observed") && result?.optBoolean("success") == true ->
                "执行器确认成功"
            else -> "结果状态未知"
        }
    }

    private fun detailOf(payload: JSONObject): String {
        val result = objectValue(payload.opt("result"))
        return listOf(result?.optString("summary").orEmpty(), result?.optString("error").orEmpty(),
            payload.optString("summary"), payload.optString("reason"))
            .firstOrNull(String::isNotBlank).orEmpty().take(230)
    }

    /**
     * [events] is the already loaded personal timeline, not a fresh unbounded
     * scan per card. Revalidate every linked row against the canonical store
     * before displaying it: deletion and a foreign role must fail closed.
     */
    fun inspect(
        context: Context,
        characterId: String,
        anchor: SharedTimelineEvent,
        events: List<SharedTimelineEvent>,
        mind: JSONObject = CharacterInnerLifeStore.historySnapshot(characterId, 200),
    ): List<CharacterCausalTraceLine> {
        if (characterId.isBlank() || anchor.characterId != characterId) return emptyList()
        val current = SharedExperienceTimeline.eventsByIds(characterId, listOf(anchor.id))
            .firstOrNull { it.revision == anchor.revision } ?: return emptyList()
        val payload = if (current.source == "tool-router") objectValue(current.content) else null
        val candidates = events.filter { it.characterId == characterId && it.id != current.id &&
            it.source == "tool-router" }.mapNotNull { event ->
            val body = objectValue(event.content) ?: return@mapNotNull null
            if (body.optString("causeEvidenceId") == current.id ||
                (current.taskId.isNotBlank() && event.taskId == current.taskId)) event to body else null
        }
        val active = SharedExperienceTimeline.eventsByIds(characterId,
            candidates.map { it.first.id }).associateBy { it.id }
        val linked = candidates.filter { (event, _) ->
            active[event.id]?.revision == event.revision
        }.sortedBy { it.first.occurredAt }
        val rows = mutableListOf<CharacterCausalTraceLine>()
        val thoughts = mind.optJSONArray("thoughts")
        val selectedThoughts = (0 until (thoughts?.length() ?: 0))
            .mapNotNull { index -> thoughts?.optJSONObject(index) }.filter {
                it.optString("evidenceId") == current.id ||
                    (0 until (it.optJSONArray("stimuli")?.length() ?: 0)).any { index ->
                        it.optJSONArray("stimuli")?.optString(index) == current.id
                    }
            }
        selectedThoughts.takeLast(4).forEach { thought ->
            val state = when (thought.optString("status")) {
                "resolved" -> "已由本人放下"
                "discarded" -> "本人已舍弃"
                "evolved" -> "已发展为其他想法"
                else -> "仍被保留，未必决定执行"
            }
            rows += CharacterCausalTraceLine("本人想法 · $state",
                thought.optString("thought").take(190))
        }
        val decisions = mind.optJSONArray("decisions")
        val relatedDecisions = (0 until (decisions?.length() ?: 0))
            .mapNotNull { index -> decisions?.optJSONObject(index) }.filter {
                it.optString("causalEvidenceId") == current.id
            }
        relatedDecisions.takeLast(6).forEach { decision ->
            val choice = decision.optString("selected")
            rows += CharacterCausalTraceLine(
                if (choice == "silent") "本人选择安静" else "本人选择 · ${CharacterRecordProjection.actionLabel(choice)}",
                listOf(decision.optString("reason"), decision.optString("outcome"))
                    .filter(String::isNotBlank).joinToString("；").take(240),
            )
        }
        if (payload != null) {
            val cause = payload.optString("causeEvidenceId")
            if (cause.isNotBlank()) {
                val source = SharedExperienceTimeline.eventsByIds(characterId, listOf(cause)).firstOrNull()
                val expectedRevision = payload.optLong("causeEvidenceRevision", 0L)
                rows += CharacterCausalTraceLine("行动依据",
                    when {
                        source == null -> "原始依据已撤回或不可见"
                        expectedRevision > 0 && expectedRevision != source.revision ->
                            "来源已被编辑；当时依据版本 $expectedRevision，当前版本 ${source.revision}，不能用新内容解释旧决定"
                        expectedRevision == 0L ->
                            "关联原始事件：${source.channel}（旧回执未保存当时的来源版本）"
                        else -> "关联原始事件：${source.channel} · 版本 $expectedRevision"
                    }, if (source == null) "" else source.id)
            }
            rows += receiptLine(context, characterId, current, payload)
            rows += receiptContinuation(context, characterId, current, payload, mind)
        }
        linked.takeLast(12).forEach { (event, body) ->
            val originalRevision = body.optLong("causeEvidenceRevision", 0L)
            val directCause = body.optString("causeEvidenceId") == current.id
            when {
                directCause && originalRevision > 0 && originalRevision != current.revision ->
                    rows += CharacterCausalTraceLine("历史版本的行动回执",
                        "这项行动引用本事件的旧版本 $originalRevision，不能视为对当前版本的回应",
                        event.id)
                directCause && originalRevision == 0L ->
                    rows += receiptLine(context, characterId, event, body).copy(
                        title = "旧回执 · 来源版本未证实",
                        detail = "旧记录未保存来源版本，不能断定是当前文字触发；" +
                            receiptLine(context, characterId, event, body).detail)
                directCause -> {
                    rows += receiptLine(context, characterId, event, body)
                    rows += receiptContinuation(context, characterId, event, body, mind)
                }
                else -> rows += receiptLine(context, characterId, event, body).let { line ->
                    line.copy(title = "同任务记录 · ${line.title}",
                        detail = "仅共享任务 ID，不证明由当前事件直接触发；${line.detail}")
                }
            }
        }
        // Resident agenda validation is a factual scheduling outcome, not a
        // ToolRouter execution. Its own role-owned rows already carry the
        // exact daily decision task ID; don't confuse rejected planning with
        // an action the resident performed or chose to cancel.
        val rejectedAgenda = if (current.source == "resident-daily") {
            val candidates = events.filter {
                it.characterId == characterId &&
                    it.source == "resident-agenda-rejected" &&
                    it.taskId == current.id &&
                    it.id.startsWith("${current.id}-agenda-rejected-")
            }
            val live = SharedExperienceTimeline.eventsByIds(characterId, candidates.map { it.id })
                .associateBy { it.id }
            candidates.filter { live[it.id]?.revision == it.revision }
                .sortedBy { it.id }.takeLast(12)
        } else emptyList()
        rejectedAgenda.forEach { rejected ->
            rows += CharacterCausalTraceLine("日程未排入 · 执行器拒绝",
                rejected.content.take(260), rejected.id)
        }
        // A saved resident agenda is a decision to schedule, NOT a tool
        // execution. Link by the durable source ID/revision recorded at save
        // time; time/title matching would invent causality after edits.
        val linkedPlans = if (current.source == "resident-daily") {
            RoleDayScheduleStore.plans(context, characterId)
                .filter { it.originEvidenceId == current.id }
                .sortedBy { it.createdAt }
                .takeLast(12)
        } else emptyList()
        linkedPlans.forEach { plan ->
            val oldRevision = plan.originEvidenceRevision
            val stale = oldRevision == 0L || oldRevision != current.revision
            val actualReceipt = plan.resultEvidenceId.takeIf(String::isNotBlank)?.let { id ->
                SharedExperienceTimeline.eventsByIds(characterId, listOf(id)).firstOrNull()
            }
            val status = when {
                stale -> "旧版本的日程"
                plan.status == "completed" && actualReceipt == null -> "日程状态待核对"
                plan.status == "completed" -> "日程执行有回执"
                plan.status == "cancelled" -> "日程已取消"
                plan.status == "planned" || plan.status == "due" -> "已排入日程，尚非执行"
                else -> "日程状态未知"
            }
            val version = when {
                oldRevision == 0L -> "旧记录未保存来源版本，不能确认当前文字就是当时的决定"
                stale -> "安排依据版本 $oldRevision，当前版本 ${current.revision}，不能用新内容解释旧安排"
                else -> "安排依据版本 $oldRevision"
            }
            val outcome = when {
                plan.status == "completed" && actualReceipt == null ->
                    "状态曾记为完成，但原执行证据已撤回或不可见"
                plan.status == "completed" -> "原执行证据仍可查，不等于本人目标已达成"
                else -> "原活动没有在此处被证明执行成功"
            }
            rows += CharacterCausalTraceLine("个人日程 · $status",
                "时间=${plan.dueAt}；活动=${plan.title.take(75)}；动作=${plan.activityAction}；$version；$outcome".take(260),
                actualReceipt?.id.orEmpty())
        }
        if (payload == null && relatedDecisions.isEmpty() && linked.isEmpty() && rejectedAgenda.isEmpty() && linkedPlans.isEmpty()) {
            rows += CharacterCausalTraceLine("后续行动", "当前可检索记录中没有精确关联的决定或执行回执；不代表本人没有想法，也不能证明已执行")
        } else if (payload == null && relatedDecisions.isNotEmpty() && linked.isEmpty() && linkedPlans.isEmpty()) {
            rows += CharacterCausalTraceLine("执行证据", "有选择记录，但未找到以这条事件为依据的工具回执；不能据此认定已经执行")
        }
        return rows
    }

    /**
     * A tool receipt can be inspected from its own card or from the original
     * source card. Only exact, live evidence IDs join the next perception and
     * decision; a shared task ID, stale revision or similar text never does.
     * No reads here claim a stimulus or schedule another model request.
     */
    private fun receiptContinuation(
        context: Context, characterId: String, receipt: SharedTimelineEvent,
        body: JSONObject, mind: JSONObject,
    ): List<CharacterCausalTraceLine> {
        val rows = mutableListOf<CharacterCausalTraceLine>()
        val result = objectValue(body.opt("result"))
        val nativeId = result?.optString("evidenceId").orEmpty()
        val native = if (nativeId.isNotBlank() && nativeId != receipt.id)
            SharedExperienceTimeline.eventsByIds(characterId, listOf(nativeId)).firstOrNull()
        else null
        if (nativeId.isNotBlank() && nativeId != receipt.id) {
            rows += CharacterCausalTraceLine("原生结果记录",
                if (native == null) "执行器引用的原始结果不存在或已撤回"
                else "真实记录：${native.channel}；不等于目标已完成",
                native?.id.orEmpty())
        }
        val causes = setOfNotNull(receipt.id, native?.id)
        val decisions = mind.optJSONArray("decisions")
        val followUps = (0 until (decisions?.length() ?: 0))
            .mapNotNull { index -> decisions?.optJSONObject(index) }
            .filter { it.optString("causalEvidenceId") in causes }
        followUps.takeLast(3).forEach { decision ->
            rows += CharacterCausalTraceLine("回执后的本人选择 · " +
                CharacterRecordProjection.actionLabel(decision.optString("selected")),
                listOf(decision.optString("reason"), decision.optString("outcome"))
                    .filter(String::isNotBlank).joinToString("；").take(240),
                receipt.id)
        }
        if (followUps.isEmpty() &&
            PerceptionStimulusLedger.peekSeen(context, characterId, receipt.id, receipt.revision)) {
            rows += CharacterCausalTraceLine("回执后的后续",
                "本人已有这条回执的感知检查点，但当前可检索的决定中没有精确后续；" +
                    "不能推断已放弃、未思考或目标完成", receipt.id)
        }
        val motiveId = body.optString("motiveId")
        if (motiveId.isNotBlank()) {
            val motives = mind.optJSONArray("motives")
            val motive = (0 until (motives?.length() ?: 0))
                .mapNotNull { index -> motives?.optJSONObject(index) }
                .firstOrNull { it.optString("id") == motiveId }
            rows += CharacterCausalTraceLine("持续目标",
                if (motive == null) "原目标当前不可检索，不能推断已完成"
                else "当前状态：${motive.optString("status")}；${motive.optString("waitingFor").take(150)}")
        }
        return rows
    }

    private fun receiptLine(context: Context, characterId: String,
        event: SharedTimelineEvent, body: JSONObject): CharacterCausalTraceLine {
        val status = receiptStatus(body)
        val checkpoint = PerceptionStimulusLedger.peekSeen(context, characterId, event.id, event.revision)
        val perception = if (checkpoint) "本人感知检查点已记录（不等于目标达成）"
            else "尚无本人感知检查点（可能待处理，不能据此断言未理解）"
        val task = body.optString("requestId").ifBlank { event.taskId }
        val request = if (task.isBlank()) "" else "；请求ID：${task.take(75)}"
        return CharacterCausalTraceLine(
            "行动回执 · ${body.optString("tool").ifBlank { "未知工具" }} · $status",
            "${detailOf(body)}；$perception$request".trimStart('；'),
            event.id,
        )
    }
}
