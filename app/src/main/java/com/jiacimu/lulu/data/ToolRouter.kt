package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import com.jiacimu.lulu.system.LuluScreenAwareness
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Shared dependency gate for an actual tool outcome, not an accepted request.
 * All scenes must use this before allowing requiresPreviousSuccess steps.
 */
internal object ToolExecutionStatus {
    private val completedStates = setOf(
        "succeeded", "observed", "notification_posted", "popup_requested",
        "verified", "already_at_target", "unchanged", "capped", "at_safe_limit",
    )

    fun completed(result: JSONObject): Boolean {
        if (!result.optBoolean("success")) return false
        val status = result.optString("status").trim().lowercase()
        // Most local action executors return only success=true, and ToolRouter
        // durably normalizes that to succeeded. Any *explicit* unknown state
        // is not completion evidence.
        return status.isBlank() || status in completedStates
    }

    /** Expected authorization/review outcomes stay in the durable tool receipt,
     * but are not transport, parser or executor failures for the performance log.
     * Explicit failures and unknown statuses remain operational diagnostics. */
    private val expectedIncompleteStates = setOf(
        "unverified", "waiting_user", "waiting_permission",
        "requested", "requested_unverified", "pending", "denied",
    )

    fun shouldReportPerformanceError(result: JSONObject): Boolean =
        !result.optBoolean("success") &&
            result.optString("status").trim().lowercase() !in expectedIncompleteStates

    /** A missing primary tool is not a failed predecessor. Ordered steps can
     * follow the character's chosen speech/silence without a phantom failure;
     * when a primary tool DID run, only its verified result unlocks dependent steps.
     */
    fun primaryAllowsDependentSteps(primaryTool: String, primaryResult: JSONObject): Boolean =
        primaryTool.isBlank() || completed(primaryResult)

    /** A durable terminal ToolRouter refusal cannot become success when
     * the same immutable request ID is replayed. Unrecorded and pending work
     * still needs recovery and must never be called settled. */
    fun terminalRejection(result: JSONObject, hasDurableReceipt: Boolean): Boolean =
        hasDurableReceipt && !result.optBoolean("success") &&
            result.optString("status").trim().lowercase() in setOf("", "failed", "denied")
}

/** The same persisted routing and evidence path is used by foreground, voice and background. */
object ToolRouter {
    private val mutex = KeyedWorkMutex()
    private val requests = KeyedWorkMutex()

    suspend fun execute(context: Context, characterId: String, name: String, args: JSONObject,
        requestId: String = UUID.randomUUID().toString(), userRequested: Boolean = false,
        intentionId: String = "", autonomousLifeTurn: Boolean = false,
        learningApplication: JSONObject? = null, causeEvidenceId: String = "",
        reason: String = "", curiosity: JSONObject? = null,
        motiveId: String = "", concernId: String = "", experienceId: String = "",
        goalReviewMode: Boolean = false,
        expectedPlanDueAt: Instant? = null,
        // A group-notice decision is a one-hop reply, not a fresh invitation
        // to recursively wake every group member again.
        groupNoticeFollowUp: Boolean = false): String = requests.withLock("$characterId:$requestId") {
        CharacterCuriosityRuntime.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterLifeFollowThroughStore.initialize(context)
        // A slow read-only website request must not hold up an in-call favorite
        // or another short action. Exact request ids still serialize across tools.
        mutex.withLock("$characterId:" + when (name) {
            "read_xiaohongshu" -> "xhs"
            "web_search" -> "web"
            "image_search" -> "images"
            com.jiacimu.lulu.ai.JevDirections.TOOL -> "thought-reference"
            else -> "actions"
        }) {
            fun rejected(message: String, status: String = "failed"): String {
                // Rejection is an executor fact, not a performed action. In the
                // old path it returned only a string: the character's next
                // perception had no durable evidence of denied permissions or
                // invalid tools, so it could repeat the same proposal forever.
                // Keep the original requestId for correlation, but a DISTINCT
                // timeline ID: later authorization must not overwrite this
                // rejection or turn it into an executed action.
                val result = failure(message, status)
                val cause = CharacterActionEvidence.resolve(characterId, causeEvidenceId)
                    ?.takeIf { it.evidenceKind in setOf(EventEvidenceKind.UserStatement,
                        EventEvidenceKind.Observation, EventEvidenceKind.CharacterStatement) ||
                        // A verified search result is a real, role-owned cause
                        // for its subsequent spoken feedback, not a model guess.
                        SearchReceiptFeedback.completedEvidence(it) }
                val evidenceId = "tool-rejected-$characterId-$requestId"
                if (SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId)).isEmpty()) {
                    val receipt = JSONObject().put("characterId", characterId)
                        .put("requestId", requestId).put("tool", name)
                        .put("status", status).put("stage", "rejected")
                        .put("result", result).put("reason", reason.take(220))
                        .put("causeEvidenceId", cause?.id.orEmpty())
                        .put("causeEvidenceRevision", cause?.revision ?: 0L)
                    SharedExperienceTimeline.record(evidenceId, characterId,
                        "工具请求未执行", "执行器", receipt.toString(),
                        source = "tool-router", taskId = requestId,
                        evidenceKind = EventEvidenceKind.ToolResult,
                        triggerExtraction = false)
                    // Reuse the SAME motive/concern/experience projection as
                    // successful ToolRouter receipts. This was a refused
                    // attempt, never a performed action or goal completion.
                    if (SharedExperienceTimeline.eventsByIds(characterId, listOf(evidenceId)).isNotEmpty())
                        projectCausalLinks(characterId, JSONObject()
                            .put("motiveId", motiveId).put("concernId", concernId)
                            .put("experienceId", experienceId),
                            evidenceId, name, false, message)
                }
                return result
            }
            // Reconcile a previously persisted request before applying CURRENT
            // permission/retirement policy. A completed effect is historical
            // fact, not a fresh tool invocation: revoking access must stop new
            // calls without hiding its old result or breaking receipt recovery.
            // An unfinished retry-safe request still needs today's permission.
            val prefs = context.getSharedPreferences("lulu_tool_executions", Context.MODE_PRIVATE)
            val key = "$characterId:$requestId"
            val fingerprint = requestFingerprint(name, args)
            val old = prefs.getString(key, null)?.let(::JSONObject)
            val cap = CapabilityRegistry.find(name)
            if (old != null) {
                // Rejected reuse is an observable fact too. Keep the original
                // completed receipt untouched; a separate rejection timeline
                // event explains why a stale request was not executed.
                // Diagnostic IDs change on retries and must not redefine a
                // previously chosen action. Old persisted fingerprints can
                // contain that field: normalize them without re-executing.
                if (normalizeStoredFingerprint(old.optString("fingerprint")) != fingerprint)
                    return@withLock rejected("同一执行ID的参数发生变化，请重新确认", "waiting_user")
                if (old.optString("motiveId") != motiveId || old.optString("concernId") != concernId ||
                    old.optString("experienceId") != experienceId)
                    return@withLock rejected("同一执行ID的关联事项发生变化，请使用新的行动ID", "waiting_user")
                // The request ID identifies ONE decision and its source, not merely
                // a tool with matching JSON args. A different chat turn or due
                // slot must never inherit a previous person's actual effect.
                // Legacy rows without these fields remain readable as history.
                if (old.has("causeEvidenceId") && old.optString("causeEvidenceId") != causeEvidenceId)
                    return@withLock rejected("同一执行ID的原始依据已变化，请使用新的行动ID", "waiting_user")
                if (old.has("expectedPlanDueAt") &&
                    old.optString("expectedPlanDueAt") != expectedPlanDueAt?.toString().orEmpty())
                    return@withLock rejected("同一执行ID的日程时间版本已变化，请使用新的行动ID", "waiting_user")
                // A retry-safe unfinished operation is NOT a completed historical
                // result. Its original evidence must still exist at the version
                // the model saw; otherwise a late retry acts on corrected text.
                if (old.optString("status") == "running" && cap?.retrySafe == true &&
                    old.optString("causeEvidenceId").isNotBlank()) {
                    val original = CharacterActionEvidence.resolve(characterId,
                        old.optString("causeEvidenceId"))
                    if (old.optLong("causeEvidenceRevision", 0L) <= 0L ||
                        original?.revision != old.optLong("causeEvidenceRevision")) {
                        return@withLock rejected("原始依据已编辑或撤回，未继续执行旧请求", "waiting_user")
                    }
                }
                if (old.optString("status") != "running" || cap?.retrySafe != true) {
                    val saved = old.optString("result")
                    val receiptId = "tool-$characterId-$requestId"
                    if (old.optString("status") != "running" && saved.isNotBlank()) {
                        // A crash after effect persistence must not erase the receipt
                        // or cause a repeated side-effect when this request is retried.
                        if (SharedExperienceTimeline.eventsByIds(characterId, listOf(receiptId)).isEmpty()) {
                            SharedExperienceTimeline.record(receiptId, characterId,
                                "实际工具执行", "执行器", old.toString(),
                                triggerExtraction = true, source = "tool-router", taskId = requestId,
                                evidenceKind = EventEvidenceKind.ToolResult)
                        }
                        val restored = SharedExperienceTimeline.eventsByIds(characterId,
                            listOf(receiptId)).firstOrNull()
                        if (restored != null) {
                            projectReceipt(context, characterId, receiptId, old, intentionId)
                            // A crash can occur after the tool receipt was
                            // persisted but before its wake was queued. Replaying
                            // that SAME request restores only the missing wake;
                            // it never executes the external side effect twice.
                            if (SearchReceiptFeedback.completedEvidence(restored)) {
                                runCatching {
                                    ProactivePerceptionScheduler.scheduleToolResultPerception(
                                        context, characterId, receiptId)
                                }.onFailure { android.util.Log.w("ToolRouter",
                                    "Restored receipt wake could not be scheduled", it) }
                            }
                        }
                    }
                    return@withLock saved.ifBlank {
                        failure("上次执行中断，结果未知；为避免重复动作，需要核实", "waiting_user")
                    }
                }
            }
            // Only a genuinely new request (or a retry-safe unfinished one)
            // may reach the executor. Retired, unknown and newly revoked tools
            // never gain execution permission through an old request ID.
            val activeCap = cap ?: return@withLock rejected("未知能力：$name")
            if (activeCap.retired) return@withLock rejected("能力已停用：$name；历史记录保留，但不能再安排或执行")
            if (!CapabilityRegistry.allows(context, characterId, activeCap, userRequested))
                return@withLock rejected("此角色尚未获准主动使用 ${activeCap.permission}", "waiting_user")
            // The same policy applies to primary and ordered side steps.
            GoalFollowUpPolicy.contactBlockReason(
                CharacterLifeStore.state(characterId).optJSONObject("intention"),
                name, autonomousLifeTurn, intentionId, goalReviewMode,
            )?.let { return@withLock rejected(it, "waiting_user") }
            // A future plan does not grant extra permissions; reject the wrong role, time
            // or tool before producing side effects, including Android phone tools.
            val scheduledPlanId = args.optString("planId")
            if (scheduledPlanId.isNotBlank() &&
                name !in setOf("reschedule_personal_activity", "cancel_personal_activity") &&
                !RoleDayScheduleStore.canBegin(context, characterId, scheduledPlanId, name, args,
                    expectedDueAt = expectedPlanDueAt))
                return@withLock rejected("这项日程已取消、未到期或所选工具与原计划不符")
            val goalReceiptId = "tool-$characterId-$requestId"
            CharacterLifeStore.markExecuting(characterId, intentionId, goalReceiptId, name)
            val verifiedCause = CharacterActionEvidence.resolve(characterId, causeEvidenceId)
                ?.takeIf { it.evidenceKind in setOf(EventEvidenceKind.UserStatement,
                        EventEvidenceKind.Observation, EventEvidenceKind.CharacterStatement) ||
                        // A verified search result is a real, role-owned cause
                        // for its subsequent spoken feedback, not a model guess.
                        SearchReceiptFeedback.completedEvidence(it) }
            val execution = JSONObject().put("characterId", characterId).put("requestId", requestId)
                .put("tool", name).put("fingerprint", fingerprint).put("status", "running")
                .put("planId", scheduledPlanId).put("startedAt", Instant.now().toString())
                .put("intentionId", intentionId).put("learningApplication", learningApplication)
                .put("motiveId", motiveId).put("concernId", concernId).put("experienceId", experienceId)
                .put("causeEvidenceId", verifiedCause?.id.orEmpty())
                .put("causeEvidenceRevision", verifiedCause?.revision ?: 0L)
                .put("expectedPlanDueAt", expectedPlanDueAt?.toString().orEmpty())
                .put("reason", reason)
                .put("curiosity", curiosity?.let { JSONObject(it.toString()).apply {
                    if (optString("sourceEvidenceId").isBlank()) put("sourceEvidenceId", causeEvidenceId)
                } })
            CharacterInnerLifeStore.beginActivityExpectation(characterId, goalReceiptId, name,
                args.optJSONObject("expectation"))
            check(prefs.edit().putString(key, execution.toString()).commit())
            // A step's curiosity is chosen BEFORE execution. Its status cannot
            // be a post-result verdict: a model may include "satisfied" or
            // "dropped" in the proposed tool metadata, but neither implies
            // that the question was answered, shared, or abandoned. Preserve
            // the original authored metadata on the durable request; register
            // only an open inquiry until a later verified role decision.
            CharacterExplorationContinuity.remember(context, characterId,
                execution.optJSONObject("curiosity")?.let { pending ->
                    JSONObject(pending.toString()).put("status", "exploring")
                }, null,
                listOf(execution.optString("causeEvidenceId")).filter(String::isNotBlank))
            // Once accepted, complete and persist the actual effect even if speech is interrupted.
            withContext(NonCancellable) {
                val result = runCatching {
                    if (name == "cloud_task") {
                        val job = CloudTaskBridge.submit(characterId, args.getString("kind"),
                            JSONObject().put("request", args.optString("request")).put("sources", args.optJSONArray("sources") ?: org.json.JSONArray()).put("notes", args.optString("notes")), requestId)
                        JSONObject().put("success", true).put("status", "pending").put("requestId", job.getString("requestId"))
                            .put("summary", "已保存云端任务请求，尚未生成文件").toString()
                    } else if (name == com.jiacimu.lulu.ai.JevDirections.TOOL) {
                        com.jiacimu.lulu.ai.JevDirections.evaluate(context, characterId, args)
                    } else if (name == "save_photo") {
                        val saved = withContext(kotlinx.coroutines.Dispatchers.IO) {
                            val messageId = args.optString("messageId").trim()
                            val searchId = args.optString("searchId").trim()
                            when {
                                messageId.isNotBlank() && searchId.isBlank() ->
                                    com.jiacimu.lulu.RolePhotoAlbumStore.saveChatMessage(context, characterId, messageId)
                                searchId.isNotBlank() && messageId.isBlank() ->
                                    com.jiacimu.lulu.RolePhotoAlbumStore.saveSearchResult(
                                        context, characterId, searchId, args.optInt("index", -1))
                                else -> error("请选择本角色实际收到的 messageId 或自己搜到的 searchId+index")
                            }
                        }
                        JSONObject().put("success", true).put("status", "succeeded")
                            .put("photoId", saved.id).put("summary",
                                "照片已真正保存到本角色手机相册（${saved.source}），可在查手机→我的→相册查看")
                            .toString()
                    } else if (name == "capture_screen") {
                        LuluScreenAwareness.capture(context, userRequested, characterId, requestId,
                            saveToAlbum = args.optBoolean("saveToAlbum", false))
                    } else if (name == "read_self_todo") {
                        com.jiacimu.lulu.SelfTodoRoleRead.execute(context, args).toString()
                    } else if (name == "start_study_watch") {
                        com.jiacimu.lulu.system.LuluStudyWatch.request(
                            context, characterId, args.optInt("minutes", 45))
                    } else if (name == "stop_study_watch") {
                        val watch = com.jiacimu.lulu.system.LuluStudyWatch
                        val stopped = watch.active(context, characterId) != null &&
                            watch.stop(context, reason = "该角色结束了学习监督")
                        JSONObject().put("success", stopped)
                            .put("summary", if (stopped) "学习监督已停止，不再检查屏幕"
                                else "该角色没有正在进行的学习监督")
                            .toString()
                    } else if (name == "schedule_commitment_review") {
                        CommitmentTaskStore.initialize(context)
                        com.jiacimu.lulu.system.LuluAlarmSystem.initialize(context)
                        val at = runCatching { Instant.parse(args.optString("at")) }.getOrNull()
                        val outcome = scheduleCommitmentReview(characterId, CommitmentTaskDraft(
                            action = "review", goal = "", targetTaskId = args.optString("taskId"),
                            dueAt = at, verificationReason = args.optString("reason")))
                        JSONObject().put("success", outcome.appliedTaskId != null)
                            .put("status", if (outcome.appliedTaskId != null) "succeeded" else "failed")
                            .put("summary", outcome.note).toString()
                    } else if (name == "verify_commitment") {
                        CommitmentTaskStore.initialize(context)
                        val taskId = args.optString("taskId").trim()
                        val task = CommitmentTaskStore.active(characterId).firstOrNull { it.id == taskId }
                        if (task == null) {
                            failure("当前角色没有这条可结案的有效约定，未修改任何任务")
                        } else {
                            val ids = args.optJSONArray("completionEvidenceIds")?.let { array ->
                                (0 until array.length()).mapNotNull { i ->
                                    array.optString(i).trim().takeIf(String::isNotBlank)
                                }
                            }.orEmpty()
                            val why = args.optString("verificationReason")
                            val proof = CommitmentOutcomeVerification.evaluate(
                                task, ids, why,
                                SharedExperienceTimeline.eventsByIds(characterId, ids),
                            )
                            if (!proof.accepted) {
                                CommitmentTaskStore.update(task.id) { old ->
                                    if (!old.status.isActive()) old else old.copy(
                                        lastVerificationReason = proof.reason,
                                        lastActionResult = "验收尚未通过：${proof.reason}；保留原目标及后续安排",
                                    )
                                }
                                JSONObject().put("success", false)
                                    .put("status", "unverified")
                                    .put("taskId", task.id)
                                    .put("summary", "尚未达到可核实的结案条件：${proof.reason}；任务保持未完成，可继续求证或复查")
                                    .toString()
                            } else {
                                task.linkedAlarmId?.let(com.jiacimu.lulu.system.LuluAlarmSystem::cancel)
                                val verified = CommitmentTaskStore.update(task.id) { old ->
                                    if (!old.status.isActive()) old else old.copy(
                                        status = CommitmentTaskStatus.Completed,
                                        completionEvidenceIds = proof.evidenceIds,
                                        lastVerificationReason = why.take(240),
                                        lastActionResult = "依据真实证据核验目标已完成：${why.take(160)}",
                                        linkedAlarmId = null, nextCheckAt = null, followUpKind = "",
                                    )
                                }
                                if (verified?.status == CommitmentTaskStatus.Completed && verified.repeat == "daily") {
                                    // Tonight's confirmed outcome should restore tomorrow's daily clock.
                                    CommitmentTaskStore.advanceDailyAfterAttempt(
                                        context, verified.id, verified.executionToken)
                                }
                                JSONObject().put("success", verified?.status == CommitmentTaskStatus.Completed)
                                    .put("taskId", task.id)
                                    .put("evidenceIds", org.json.JSONArray(proof.evidenceIds))
                                    .put("summary", if (verified?.status == CommitmentTaskStatus.Completed)
                                        "核验通过，目标已结案" else "任务状态已变化，未能结案")
                                    .toString()
                            }
                        }
                    } else if (name == "retire_development_record") {
                        CharacterDevelopmentStore.initialize(context)
                        val id = args.optString("developmentId").trim()
                        val version = args.optInt("version", -1)
                        val explanation = args.optString("reason").trim()
                        if (id.isBlank() || version < 1 || explanation.isBlank()) {
                            failure("整理成长认识需要原记录ID、当前version和本人决定放下的理由")
                        } else {
                            val retired = CharacterDevelopmentStore.retireSelfAssessment(
                                characterId, id, version)
                            JSONObject().put("success", retired)
                                .put("status", if (retired) "succeeded" else "failed")
                                .put("developmentId", id)
                                .put("summary", if (retired)
                                    "这条个人成长认识已退出当前有效倾向，原记录和依据保留；理由：${explanation.take(200)}"
                                else "未找到本角色当前版本的可归档成长倾向；主人亲口事实或偏好不可通过此工具撤销")
                                .toString()
                        }
                    } else if (name == "summon_lulu") {
                        LuluScreenAwareness.summon(context, characterId)
                    } else if (activeCap.domain == "phone") {
                        LuluDeviceToolBridge.executeRegistered(context, characterId, name, args, requestId)
                    } else CompanionActionRuntime.execute(context, characterId, name, args,
                        selfInitiatedLifeTurn = autonomousLifeTurn && !userRequested).asJson()
                }.getOrElse { failure(it.message.orEmpty()) }
                val parsed = runCatching { JSONObject(result) }.getOrElse {
                    JSONObject().put("success", false).put("error", result.take(400))
                }
                if (!parsed.optBoolean("success") &&
                    parsed.optString("error").isBlank() && parsed.optString("summary").isBlank()) {
                    parsed.put("error", failureDetail(name, parsed))
                }
                val receiptId = "tool-$characterId-$requestId"
                // Prefer the actual outbound message/post. All other operations use
                // the authoritative tool receipt rather than a model description.
                if (parsed.optString("evidenceId").isBlank()) parsed.put("evidenceId", receiptId)
                val confirmedResult = parsed.toString()
                execution.put("status", parsed.optString("status").ifBlank { if (parsed.optBoolean("success")) "succeeded" else "failed" })
                    .put("result", confirmedResult).put("finishedAt", Instant.now().toString())
                check(prefs.edit().putString(key, execution.toString()).commit())
                // An evidence gate saying "not yet proven" is an expected
                // domain outcome, not an API/parser/device failure. Still
                // persist its full receipt so the same role can reconsider.
                if (ToolExecutionStatus.shouldReportPerformanceError(parsed)) {
                    com.jiacimu.lulu.LuluRepositories.performance.recordError(
                        source = "角色行动",
                        title = "${MigratedDomainStores.characters.get(characterId).displayName} · $name",
                        message = "角色ID=$characterId；执行ID=$requestId\n" +
                            parsed.optString("summary").ifBlank { parsed.optString("error").ifBlank { "执行器未确认成功" } },
                    )
                }
                SharedExperienceTimeline.record("tool-$characterId-$requestId", characterId, "实际工具执行", "执行器",
                    execution.toString(), source = "tool-router", taskId = requestId,
                    sessionId = if (name == "send_group_message") parsed.optString("conversationId") else "",
                    evidenceKind = EventEvidenceKind.ToolResult,
                    // Failures must inform the next perception, not increase
                    // the gauge for pleasant/share-worthy lived experiences.
                    triggerExtraction = parsed.optBoolean("success"))
                projectReceipt(context, characterId, receiptId, execution, intentionId)
                // Group delivery is an actual persisted event, not a character
                // saying it happened. Give peers a bounded independent choice
                // only from the initiating send, never from its own follow-up.
                // Foreground director-generated turns already own their room
                // round and must not start a second background response round.
                if (name == "send_group_message" &&
                    ToolExecutionStatus.completed(parsed) &&
                    !groupNoticeFollowUp && !requestId.startsWith("group-")) {
                    val deliveredIds = parsed.optJSONArray("deliveredMessageIds")
                    val lastMessageId = if (deliveredIds != null && deliveredIds.length() > 0)
                        deliveredIds.optString(deliveredIds.length() - 1) else
                        parsed.optString("evidenceId").takeUnless { it == receiptId }.orEmpty()
                    if (lastMessageId.isNotBlank()) runCatching {
                        ProactivePerceptionScheduler.scheduleGroupMessageReview(context,
                            characterId, parsed.optString("conversationId"), lastMessageId)
                    }.onFailure { android.util.Log.w("ToolRouter",
                        "Could not enqueue group peers; delivered message remains saved", it) }
                }
                // Central receipt-to-perception handoff. All scenes and all initiators use
                // this exact persisted tool event and the ordinary character decision loop.
                // Pending permissions are not returned knowledge; failures are still useful.
                if (name in SearchReceiptFeedback.resultTools &&
                    SharedExperienceTimeline.eventsByIds(characterId, listOf(receiptId))
                        .any(SearchReceiptFeedback::completedEvidence)) {
                    runCatching {
                        ProactivePerceptionScheduler.scheduleToolResultPerception(
                            context, characterId, receiptId)
                    }.onFailure { android.util.Log.w("ToolRouter",
                        "Could not enqueue result perception; receipt remains durable", it) }
                }
                // A verified completed experience (not a pending request or a
                // replayed request ID) can ease digital monotony once.
                if (parsed.optBoolean("success") &&
                    execution.optString("status") !in setOf("pending", "waiting_user", "requested", "running") &&
                    name in setOf("web_search", "image_search", "read_xiaohongshu",
                        "read_book", "play_solo_game")) {
                    runCatching { EmbodiedBodyRuntime.onExplorationReceipt(characterId) }
                }
                val outcome = parsed
                // A delivered chat bubble does not automatically buy another five-minute
                // online lease. Only the character's next expiry decision can do that.
                val chatDelivery = name in setOf(
                    "send_private_message", "send_group_message",
                    "send_private_sticker", "send_group_sticker",
                )
                // An autonomous tool that finished while the role explicitly
                // chose offline cannot implicitly wake it for another five minutes.
                // During a real online window, activities may still refresh it.
                if (outcome.optBoolean("success") && !chatDelivery &&
                    CompanionOnlineStore.isOnline(characterId))
                    CompanionOnlineStore.recordActivity(characterId)
                confirmedResult
            }
        }
    }

    /** Both first execution and crash recovery project the SAME frozen receipt.
     * Permissions pending are not outcomes. Recovery never repeats the real tool effect. */
    private fun projectReceipt(context: Context, characterId: String, receiptId: String,
        execution: JSONObject, legacyIntentionId: String) {
        val event = CharacterActionEvidence.resolve(characterId, receiptId) ?: return
        val receipt = CharacterExperienceLearning.receipt(event) ?: return
        val result = runCatching { JSONObject(execution.optString("result")) }.getOrNull() ?: return
        val finishedAt = runCatching { Instant.parse(execution.optString("finishedAt")) }
            .getOrDefault(event.occurredAt)
        fun project(label: String, block: () -> Unit) {
            runCatching(block).onFailure { android.util.Log.w("CharacterReceipt", "$label projection failed", it) }
        }
        project("Learning and causal links") { projectLearning(characterId, receiptId, execution) }
        project("Activity expectation") {
            CharacterInnerLifeStore.resolveActivityExpectation(characterId, receiptId,
                result.optString("evidenceId").ifBlank { receiptId }, receipt.success, receipt.summary)
        }
        project("Legacy goal") {
            CharacterLifeStore.recordLinkedOutcome(characterId,
                execution.optString("intentionId").ifBlank { legacyIntentionId }, receiptId,
                receipt.tool, receipt.success, receipt.summary)
        }
        val planId = execution.optString("planId")
        if (receipt.success && planId.isNotBlank()) project("Personal schedule") {
            val args = JSONObject(execution.optString("fingerprint").substringAfter(':'))
            RoleDayScheduleStore.completeFromReceipt(context, characterId, planId, receipt.tool,
                result.optString("evidenceId").ifBlank { receiptId }, receipt.summary, finishedAt, args)
        }
        project("Development follow-through") { CharacterDevelopmentRuntime.request(characterId) }
    }

    private fun projectLearning(characterId: String, receiptId: String, execution: JSONObject) {
        val event = CharacterActionEvidence.resolve(characterId, receiptId) ?: return
        val receipt = CharacterExperienceLearning.receipt(event) ?: return
        val result = runCatching { JSONObject(execution.optString("result")) }.getOrNull() ?: return
        val outboundAction = when (receipt.tool) {
            "send_private_message", "send_group_message" -> "message"
            "publish_moment" -> "moment"
            "write_journal" -> "journal"
            else -> receipt.tool
        }
        val native = CharacterActionEvidence.resolve(characterId, result.optString("evidenceId"))
            ?.takeIf { CharacterActionEvidence.supports(it, outboundAction) }
        projectCausalLinks(characterId, execution, native?.id ?: receiptId,
            if (native != null) outboundAction else receipt.tool, receipt.success, receipt.summary)
        CharacterLearningLedger.recordApplication(characterId, execution.optJSONObject("learningApplication"),
            native?.id ?: receiptId, if (native != null) outboundAction else receipt.tool,
            receipt.success, receipt.summary)
        execution.optJSONObject("curiosity")?.let { inquiry ->
            // This metadata was authored BEFORE the result was available. It
            // cannot claim that the answer is already satisfactory afterwards.
            if (receipt.success) CharacterCuriosityRuntime.recordOutcome(characterId,
                JSONObject(inquiry.toString()).put("status", "exploring"), receipt.tool,
                true, receipt.summary, native?.id ?: receiptId)
            else CharacterCuriosityRuntime.recordFailure(characterId, inquiry, receipt.tool, receipt.summary)
        }
    }

    /** Permission acceptance is not the screenshot. Apply only the later real observation. */
    internal fun projectDeferredScreenLearning(characterId: String, requestId: String, observationId: String) {
        val original = CharacterActionEvidence.resolve(characterId, "tool-$characterId-$requestId") ?: return
        val execution = runCatching { JSONObject(original.content) }.getOrNull() ?: return
        val observed = CharacterActionEvidence.resolve(characterId, observationId) ?: return
        val receipt = CharacterExperienceLearning.receipt(observed) ?: return
        if (receipt.requestEvidenceId != original.id) return
        CharacterInnerLifeStore.resolveActivityExpectation(characterId, original.id, observed.id,
            receipt.success, receipt.summary)
        projectCausalLinks(characterId, execution, observed.id, "capture_screen", receipt.success, receipt.summary)
        CharacterLearningLedger.recordApplication(characterId, execution.optJSONObject("learningApplication"),
            observed.id, "capture_screen", receipt.success, receipt.summary)
        execution.optJSONObject("curiosity")?.let { inquiry ->
            if (receipt.success) CharacterCuriosityRuntime.recordOutcome(characterId,
                JSONObject(inquiry.toString()).put("status", "exploring"), "capture_screen",
                true, receipt.summary, observed.id)
            else CharacterCuriosityRuntime.recordFailure(characterId, inquiry, "capture_screen", receipt.summary)
        }
        CharacterDevelopmentRuntime.request(characterId)
    }

    /** Frozen per-step metadata follows only that step's real receipt, including replay recovery. */
    private fun projectCausalLinks(role: String, execution: JSONObject, receiptId: String,
                                  action: String, success: Boolean, summary: String) {
        CharacterInnerLifeStore.recordActionResult(role, execution.optString("motiveId"),
            receiptId, action, success, summary)
        CharacterInnerLifeStore.recordConcernOutcome(role, execution.optString("concernId"),
            receiptId, action, success, summary)
        CharacterLifeFollowThroughStore.recordAction(role, execution.optString("experienceId"),
            action, success, summary, receiptId)
    }

    /** Human-readable diagnostics even when native tools return status/verification only. */
    internal fun failureDetail(name: String, result: JSONObject): String {
        val available = listOf("error", "summary", "verification", "message",
            "detail", "reason", "status").firstNotNullOfOrNull { key ->
            result.optString(key).takeIf { it.isNotBlank() }
        }
        return (available ?: "工具${name}执行未成功，且执行器没有提供诊断字段").take(250)
    }

    private fun failure(message: String, status: String = "failed"): String = JSONObject().put("success", false).put("status", status).put("error", message).toString()

    /** Only business parameters identify an effect; _turnTraceId is local telemetry. */
    internal fun requestFingerprint(name: String, args: JSONObject): String =
        "$name:${canonical(JSONObject(args.toString()).apply { remove("_turnTraceId") })}"

    /** Read old durable rows that included the pre-fix trace, without mutating history. */
    private fun normalizeStoredFingerprint(stored: String): String {
        val action = stored.substringBefore(':')
        if (action == stored) return stored
        val storedArgs = runCatching { JSONObject(stored.substringAfter(':')) }.getOrNull()
            ?: return stored
        return requestFingerprint(action, storedArgs)
    }

    private fun canonical(value: Any?): String = when (value) {
        is JSONObject -> value.keys().asSequence().toList().sorted().joinToString(",", "{", "}") { JSONObject.quote(it) + ":" + canonical(value.get(it)) }
        is org.json.JSONArray -> (0 until value.length()).joinToString(",", "[", "]") { canonical(value.get(it)) }
        null, JSONObject.NULL -> "null"
        is String -> JSONObject.quote(value)
        is Number, is Boolean -> value.toString()
        else -> JSONObject.quote(value.toString())
    }
}
