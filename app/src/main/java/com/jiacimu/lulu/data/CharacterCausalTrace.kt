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

    /** The ordinary 200-entry mind window is only a UI working set. An old
     * event can have a genuine decision thousands of turns earlier; retrieve
     * that exact source from the existing private history archive on demand. */
    private fun decisionsForSource(characterId: String, evidenceId: String,
        mind: JSONObject): List<JSONObject> {
        val recent = mind.optJSONArray("decisions")
        val fromMind = (0 until (recent?.length() ?: 0))
            .mapNotNull { recent?.optJSONObject(it) }
            .filter { it.optString("causalEvidenceId") == evidenceId }
        val archived = CharacterInnerLifeStore.decisionsForEvidence(characterId, evidenceId)
        return (archived + fromMind)
            .distinctBy { decision ->
                decision.optString("id").ifBlank { decision.toString() }
            }
            .sortedBy { it.optString("at") }
            .takeLast(6)
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
        val fromMind = (0 until (thoughts?.length() ?: 0))
            .mapNotNull { index -> thoughts?.optJSONObject(index) }.filter {
                it.optString("evidenceId") == current.id ||
                    (0 until (it.optJSONArray("stimuli")?.length() ?: 0)).any { index ->
                        it.optJSONArray("stimuli")?.optString(index) == current.id
                    }
            }
        // The 200-entry screen snapshot cannot cover years of impulses.
        // Read only this exact source from the existing role-owned archive.
        val selectedThoughts = (CharacterInnerLifeStore.thoughtsForEvidence(
            characterId, current.id) + fromMind)
            .distinctBy { thought ->
                thought.optString("thought") to thought.optString("at")
            }.sortedBy { it.optString("at") }.takeLast(4)
        selectedThoughts.forEach { thought ->
            val state = when (thought.optString("status")) {
                "resolved" -> "已由本人放下"
                "discarded" -> "本人已舍弃"
                "evolved" -> "已发展为其他想法"
                else -> "仍被保留，未必决定执行"
            }
            val version = thought.optJSONObject("stimulusRevisions")
                ?.optLong(current.id, 0L)?.takeIf { it > 0L }
                ?: if (thought.optString("evidenceId") == current.id)
                    thought.optLong("evidenceRevision", 0L) else 0L
            val provenance = when {
                version == 0L -> " · 来源版本未证实"
                version != current.revision -> " · 历史版本 $version"
                else -> ""
            }
            val warning = if (version > 0L && version != current.revision)
                "来源已编辑，不能用当前文字解释旧想法；" else ""
            rows += CharacterCausalTraceLine("本人想法 · $state$provenance",
                (warning + thought.optString("thought")).take(230))
        }
        val relatedDecisions = decisionsForSource(characterId, current.id, mind)
        // The timeline card receives a bounded UI window. An older search
        // receipt can fall outside it even though the original model choice
        // preserved the exact ToolRouter request IDs. Recover ONLY those
        // role-owned IDs, with the same source revision and decision time;
        // never scan all history or infer a match from similar text.
        val exactRequestIds = if (payload != null) emptyList() else relatedDecisions
            .filter { it.optLong("causalEvidenceRevision", 0L) == current.revision }
            .flatMap { decision ->
                val base = decision.optString("executionRequestId").trim()
                if (base.isBlank()) emptyList() else buildList {
                    if (decision.optBoolean("executionHasPrimary", true)) add(base)
                    val explicit = decision.optJSONArray("executionStepRequestIds")
                        ?.takeIf { it.length() > 0 }
                    repeat(decision.optInt("executionStepCount", 0).coerceIn(0, 24)) { index ->
                        val stepId = if (explicit == null) "$base:step:$index"
                            else explicit.optString(index).trim()
                        if (stepId.isNotBlank()) add(stepId)
                    }
                }
            }.distinct()
        val exactReceipts = SharedExperienceTimeline.eventsByIds(characterId,
            exactRequestIds.flatMap { id ->
                listOf("tool-$characterId-$id", "tool-rejected-$characterId-$id")
            }).mapNotNull { event ->
            if (event.source != "tool-router" ||
                event.evidenceKind != EventEvidenceKind.ToolResult) return@mapNotNull null
            val body = objectValue(event.content) ?: return@mapNotNull null
            val requestId = body.optString("requestId")
            if (requestId !in exactRequestIds ||
                body.optString("causeEvidenceId") != current.id ||
                body.optLong("causeEvidenceRevision", 0L) != current.revision) return@mapNotNull null
            val matchingDecision = relatedDecisions.any { decision ->
                if (decision.optLong("causalEvidenceRevision", 0L) != current.revision)
                    return@any false
                val base = decision.optString("executionRequestId").trim()
                val explicit = decision.optJSONArray("executionStepRequestIds")
                    ?.takeIf { it.length() > 0 }
                val belongsToDecision = (decision.optBoolean("executionHasPrimary", true) &&
                    requestId == base && base.isNotBlank()) ||
                    (0 until decision.optInt("executionStepCount", 0).coerceIn(0, 24))
                        .any { index ->
                            requestId == if (explicit == null) "$base:step:$index"
                                else explicit.optString(index).trim()
                        }
                belongsToDecision && runCatching {
                    java.time.Instant.parse(decision.optString("at"))
                }.getOrNull()?.let { !event.occurredAt.isBefore(it) } == true
            }
            if (!matchingDecision) return@mapNotNull null
            event to body
        }
        val linkedReceipts = (linked + exactReceipts).distinctBy { it.first.id }
            .sortedBy { it.first.occurredAt }
        relatedDecisions.forEach { decision ->
            val choice = decision.optString("selected")
            val revision = decision.optLong("causalEvidenceRevision", 0L)
            val versionLabel = when {
                revision == 0L -> " · 旧记录来源版本未证实"
                revision != current.revision -> " · 历史版本 $revision"
                else -> ""
            }
            val detail = listOf(decision.optString("reason"), decision.optString("outcome"))
                .filter(String::isNotBlank).joinToString("；")
            rows += CharacterCausalTraceLine(
                (if (decision.optString("executionState") == "pending")
                    "本人已选择 · 执行总结未确认 · ${CharacterRecordProjection.actionLabel(choice)}"
                else if (choice == "silent") "本人选择安静" else
                    "本人选择 · ${CharacterRecordProjection.actionLabel(choice)}") + versionLabel,
                (if (revision > 0L && revision != current.revision)
                    "当前来源版本为 ${current.revision}，不能用新文字解释旧选择；" else "") +
                    detail.take(210),
            )
            rows += executionRequestLines(context, characterId, decision,
                historical = revision == 0L || revision != current.revision)
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
            // The direct result card must not depend on a recent-events window
            // to locate the later verified reply's durable request ID.
            rows += searchFeedbackLines(context, characterId, current, payload, events)
                .filterNot { feedback -> linked.any { it.first.id == feedback.evidenceId } }
        }
        linkedReceipts.takeLast(12).forEach { (event, body) ->
            val originalRevision = body.optLong("causeEvidenceRevision", 0L)
            val directCause = body.optString("causeEvidenceId") == current.id
            when {
                directCause && originalRevision > 0 && originalRevision != current.revision ->
                    rows += CharacterCausalTraceLine("历史版本的行动回执",
                        "这项行动引用本事件的旧版本 $originalRevision，不能视为对当前版本的回应",
                        event.id)
                directCause && originalRevision == 0L ->
                    rows += receiptLine(context, characterId, event, body).let { line ->
                        line.copy(
                            // Preserve the actual pending/failed/permission
                            // status even when the old cause revision is
                            // unknown; provenance uncertainty is not the
                            // same thing as an unknown execution outcome.
                            title = "旧回执 · 来源版本未证实 · ${line.title}",
                            detail = "旧记录未保存来源版本，不能断定是当前文字触发；" +
                                line.detail)
                    }
                directCause -> {
                    rows += receiptLine(context, characterId, event, body)
                    rows += receiptContinuation(context, characterId, event, body, mind)
                    // The first user event caused the search, while the search
                    // receipt caused the final spoken answer. Do not hide that
                    // second edge merely because it is not a direct child of
                    // the user's original evidence ID.
                    rows += searchFeedbackLines(context, characterId, event, body, events)
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
            // The expanded card's [events] is only a recent UI page. A settled
            // rejection must remain visible years later even if its receipt
            // has left that page. The original timeline owns these stable IDs.
            SharedExperienceTimeline.eventsByIdPrefix(
                characterId, "${current.id}-agenda-rejected-")
                .filter { it.source == "resident-agenda-rejected" &&
                    it.taskId == current.id }
                .sortedBy { it.id }.takeLast(12)
        } else emptyList()
        rejectedAgenda.forEach { rejected ->
            val provenance = if (current.revision > 1L)
                "；原日决定文字已编辑，旧拒绝不能证明针对当前版本" else ""
            rows += CharacterCausalTraceLine("日程未排入 · 执行器拒绝",
                (rejected.content + provenance).take(300), rejected.id)
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
        if (payload == null && relatedDecisions.isEmpty() && linkedReceipts.isEmpty() && rejectedAgenda.isEmpty() && linkedPlans.isEmpty()) {
            rows += CharacterCausalTraceLine("后续行动", "当前可检索记录中没有精确关联的决定或执行回执；不代表本人没有想法，也不能证明已执行")
        } else if (payload == null && relatedDecisions.isNotEmpty() && linkedReceipts.isEmpty() && linkedPlans.isEmpty()) {
            rows += CharacterCausalTraceLine("执行证据", "有选择记录，但未找到以这条事件为依据的工具回执；不能据此认定已经执行")
        }
        return rows
    }

    /** A model selection is not a tool result. Follow the executor's exact
     * stable request IDs (primary and each ordered step), including rejected
     * attempts. A shared source/task ID alone cannot prove that a particular
     * decision executed. Reading this view never restores or retries effects. */
    private fun executionRequestLines(
        context: Context, characterId: String, decision: JSONObject,
        historical: Boolean,
    ): List<CharacterCausalTraceLine> {
        val requestId = decision.optString("executionRequestId")
        if (requestId.isBlank()) return emptyList() // Legacy decision: unknown lineage.
        val steps = decision.optInt("executionStepCount", 0).coerceIn(0, 24)
        // Older decisions use the OrderedCharacterActions convention. Phone
        // side effects retain their original stable "reply-side-...-N" IDs:
        // never invent a new ID when an explicit list was saved.
        val explicitStepIds = decision.optJSONArray("executionStepRequestIds")
            ?.takeIf { it.length() > 0 }
        val ids = buildList {
            if (decision.optBoolean("executionHasPrimary", true)) add("主动作" to requestId)
            repeat(steps) { index ->
                val stepId = if (explicitStepIds == null) "$requestId:step:$index"
                    else explicitStepIds.optString(index).trim()
                if (stepId.isNotBlank()) add("第${index + 1}步" to stepId)
            }
        }
        if (ids.isEmpty()) return emptyList() // Genuine silence with no external action.
        val evidenceIds = ids.flatMap { (_, id) ->
            listOf("tool-$characterId-$id", "tool-rejected-$characterId-$id")
        }
        val actual = SharedExperienceTimeline.eventsByIds(characterId, evidenceIds)
            .associateBy { it.id }
        val prefs = context.getSharedPreferences("lulu_tool_executions", Context.MODE_PRIVATE)
        val prefix = if (historical) "历史选择的" else "本人选择对应的"
        val chosenSource = decision.optString("causalEvidenceId")
        val chosenRevision = decision.optLong("causalEvidenceRevision", 0L)
        val decidedAt = runCatching {
            java.time.Instant.parse(decision.optString("at"))
        }.getOrNull()
        // An executor request ID is necessary but not sufficient to attribute
        // an old receipt to THIS choice. A reused/legacy ID may belong to a
        // different source or revision; do not turn that effect into success.
        fun provenanceProblem(body: JSONObject, occurredAt: java.time.Instant?): String? = when {
            chosenSource.isBlank() || chosenRevision <= 0L ->
                "本人选择未保存可核对的原始依据版本"
            body.optString("causeEvidenceId") != chosenSource ->
                "执行记录绑定了另一条原始依据"
            body.optLong("causeEvidenceRevision", 0L) != chosenRevision ->
                "执行记录的原始依据版本与本人选择不同"
            decidedAt == null -> "本人选择时间缺失"
            occurredAt == null -> "执行记录时间缺失"
            occurredAt.isBefore(decidedAt) -> "执行记录早于本人选择"
            else -> null
        }
        return ids.flatMap { (slot, id) ->
            val found = listOf("tool-$characterId-$id", "tool-rejected-$characterId-$id")
                .mapNotNull(actual::get)
                .filter { event ->
                    event.source == "tool-router" &&
                        event.evidenceKind == EventEvidenceKind.ToolResult &&
                        objectValue(event.content)?.optString("requestId") == id
                }
            if (found.isNotEmpty()) found.map { event ->
                val body = objectValue(event.content)!!
                val line = receiptLine(context, characterId, event, body)
                val problem = provenanceProblem(body, event.occurredAt)
                if (problem == null) line.copy(title = "$prefix$slot · ${line.title}")
                else CharacterCausalTraceLine(
                    "$prefix$slot · 回执归属未证实",
                    "$problem；另有实际回执（${line.title}），但不能证明这次选择执行了它；${line.detail}",
                    event.id,
                )
            } else {
                // A process may die after the durable request claim but before
                // its independent timeline receipt. Read the original claim,
                // but never borrow another source's persisted result.
                val persisted = prefs.getString("$characterId:$id", null)
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.takeIf { it.optString("characterId") == characterId &&
                        it.optString("requestId") == id }
                val persistedAt = persisted?.optString("startedAt")?.takeIf(String::isNotBlank)
                    ?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
                val problem = persisted?.let { provenanceProblem(it, persistedAt) }
                val detail = when {
                    persisted == null -> "没有找到该请求的持久化执行记录或回执；不能断定执行过"
                    problem != null ->
                        "执行记录归属未证实（$problem）；结果尚未确认属于本次选择，不能据此认定执行过"
                    persisted.optString("status") == "running" ->
                        "执行请求已持久化，结果尚未确认；不得盲目重复副作用"
                    persisted.optString("result").isNotBlank() ->
                        "执行器保存了结果，但独立时间线回执缺失；须恢复或核对原回执"
                    else -> "执行请求已保存，但结果尚未确认"
                }
                listOf(CharacterCausalTraceLine("$prefix$slot · 回执未找到",
                    "$detail；请求ID：${id.take(75)}"))
            }
        }
    }

    /**
     * A final search reply is a separate ToolRouter effect. Follow only its
     * exact, revision-matched search receipt ID (never a shared task, title or
     * native URL); verify the live record and keep failed delivery distinct.
     * The receipt card already lists its direct child through [linked].
     */
    private fun searchFeedbackLines(
        context: Context, characterId: String, receipt: SharedTimelineEvent,
        receiptBody: JSONObject, events: List<SharedTimelineEvent>,
    ): List<CharacterCausalTraceLine> {
        if (receiptBody.optString("tool") !in SearchReceiptFeedback.resultTools) return emptyList()
        val requestId = receiptBody.optString("requestId").trim()
        // Verified feedback uses the original decision request ID, even when
        // the search itself was an ordered :step:N action. Exact lookup is
        // indexed by the existing timeline primary key and survives years of
        // later events. A missing/legacy request ID still uses visible rows.
        val rootRequestId = requestId.replace(Regex(":step:\\d+$"), "")
        val exactFeedback = if (rootRequestId.isBlank()) emptyList() else
            SharedExperienceTimeline.eventsByIds(characterId, listOf(
                "tool-$characterId-$rootRequestId:verified-search-feedback",
                "tool-rejected-$characterId-$rootRequestId:verified-search-feedback"))
        val candidates = (events + exactFeedback).distinctBy { it.id }.filter { event ->
            if (event.characterId != characterId || event.source != "tool-router" ||
                event.evidenceKind != EventEvidenceKind.ToolResult ||
                event.occurredAt.isBefore(receipt.occurredAt)) return@filter false
            val body = objectValue(event.content) ?: return@filter false
            body.optString("tool") == "send_private_message" &&
                body.optString("requestId").endsWith(":verified-search-feedback") &&
                body.optString("causeEvidenceId") == receipt.id &&
                body.optLong("causeEvidenceRevision", 0L) == receipt.revision
        }
        val active = SharedExperienceTimeline.eventsByIds(characterId,
            candidates.map { it.id }).associateBy { it.id }
        return candidates.filter { active[it.id]?.revision == it.revision }
            .sortedBy { it.occurredAt }.takeLast(3).mapNotNull { event ->
                val body = objectValue(event.content) ?: return@mapNotNull null
                receiptLine(context, characterId, event, body).let { line ->
                    line.copy(title = "搜索结果后的实际反馈 · ${line.title}")
                }
            }
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
        val causes = listOf(receipt, native).filterNotNull().distinctBy { it.id }
        val sourcedDecisions = causes.flatMap { source ->
            decisionsForSource(characterId, source.id, mind).map { source to it }
        }.distinctBy { (_, decision) ->
            decision.optString("id").ifBlank { decision.toString() }
        }.sortedBy { (_, decision) -> decision.optString("at") }

        // A matching evidence ID alone does not establish that the choice was
        // made AFTER the tool returned. Late writes, old imports and edits can
        // otherwise turn an earlier intention into a fabricated follow-up.
        // Require both the source revision and an actual decision timestamp.
        fun unverifiedReason(source: SharedTimelineEvent, decision: JSONObject): String? {
            val revision = decision.optLong("causalEvidenceRevision", 0L)
            val decidedAt = runCatching {
                java.time.Instant.parse(decision.optString("at"))
            }.getOrNull()
            return when {
                revision == 0L -> "旧选择未记录依据版本"
                revision != source.revision ->
                    "选择依据历史版本 $revision，当前结果版本 ${source.revision}"
                decidedAt == null -> "选择未记录可核对的时间"
                decidedAt.isBefore(source.occurredAt) ->
                    "选择时间早于实际结果，不能视为结果后的决定"
                else -> null
            }
        }
        val confirmed = sourcedDecisions.filter { (source, decision) ->
            unverifiedReason(source, decision) == null
        }.takeLast(3)
        confirmed.forEach { (source, decision) ->
            rows += CharacterCausalTraceLine("回执后的本人选择 · " +
                CharacterRecordProjection.actionLabel(decision.optString("selected")),
                listOf(decision.optString("reason"), decision.optString("outcome"))
                    .filter(String::isNotBlank).joinToString("；").take(210),
                source.id)
        }
        sourcedDecisions.filter { (source, decision) ->
            unverifiedReason(source, decision) != null
        }.takeLast(3).forEach { (source, decision) ->
            rows += CharacterCausalTraceLine("关联选择 · 后续关系未证实",
                ("${unverifiedReason(source, decision)}；" +
                    listOf(decision.optString("reason"), decision.optString("outcome"))
                        .filter(String::isNotBlank).joinToString("；")).take(230),
                source.id)
        }
        if (confirmed.isEmpty() &&
            PerceptionStimulusLedger.peekSeen(context, characterId, receipt.id, receipt.revision)) {
            rows += CharacterCausalTraceLine("回执后的后续",
                "本人已有这条回执的感知检查点，但没有可证实发生在结果之后的选择；" +
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
