package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.ModelConnection
import org.json.JSONArray
import java.time.Instant
import java.time.ZoneId

/** A failed, optional promise extraction must not hold the request for the
 * provider's ~125s relay 524 and block repeated scans. Keep the real result
 * unacknowledged, and rely on stable source-turn dedup for ambiguous delivery.
 * The primary chat/phone model keeps its independent full read budget.
 */
internal const val COMMITMENT_RECOVERY_READ_TIMEOUT_MS = 60_000

/** Identity of the *source turn*, not the shape of a recovery prompt. */
internal fun commitmentRecoveryEvidenceKey(turnKey: String?): String? =
    turnKey?.takeIf(String::isNotBlank)?.let { "commitment-source-turn:$it" }

/** Background-only extraction: never inherit the character's expensive chat model. */
internal fun commitmentBackgroundConnection(): ModelConnection =
    MemoryModelRuntime.extractionConnection()
        ?: LuluAiServices.connectionStore.resolveConnection()

internal suspend fun extractCommitmentTaskDrafts(
    characterId: String,
    userText: String,
    characterText: String,
    activeTasks: List<CommitmentTask>,
    recentContext: String = "",
    now: Instant = Instant.now(),
    zone: ZoneId = ZoneId.systemDefault(),
    reconcileDeliveredReply: Boolean = false,
    /** Optional source turn identity, shared by fast wake and slow recovery.
     * Prevents a second paid request after an ambiguous 524 from the same turn.
     */
    recoveryTurnKey: String? = null,
): List<CommitmentTaskDraft> {
    val result = LuluAiServices.gateway.generate(
        characterId = characterId,
        facts = buildString {
            appendLine(CommitmentDecisionProtocol.context(activeTasks, now, zone))
            if (recentContext.isNotBlank()) appendLine("此前真实对话：\n$recentContext")
            appendLine("当前时间=$now")
            appendLine("当前时区=${zone.id}")
            appendLine("用户本轮：$userText")
            appendLine("角色本轮完整回复：$characterText")
            if (activeTasks.isNotEmpty()) {
                appendLine("现有未完成任务（完整列表，不能因数量多忽略旧任务）：")
                activeTasks.forEach { task ->
                    appendLine("- id=${task.id}; goal=${task.goal}; deliveryAction=${task.deliveryAction}; repeat=${task.repeat}; dueAt=${task.dueAt}; status=${task.status}; revision=${task.revision}")
                }
            }
        },
        instruction = CommitmentDecisionProtocol.instruction +
            (if (reconcileDeliveredReply) "\n" + CommitmentDecisionProtocol.deliveredReplyReconciliation else "") +
            "\n只处理本轮已经表达或确认的约定；旧对话只用于理解指代，不重复创建。只返回约定变更JSON数组，没有变化返回[]。不返回thoughts/motives等心理状态。",
        source = "承诺任务",
        title = "承诺任务提取",
        maxTokens = 2_400,
        readTimeoutMillis = COMMITMENT_RECOVERY_READ_TIMEOUT_MS,
        contextMode = CompanionContextMode.Isolated,
        connectionOverride = commitmentBackgroundConnection(),
        backgroundEvidenceKey = commitmentRecoveryEvidenceKey(recoveryTurnKey),
    )
    // A transport failure or cancelled request is NOT "no promises found".
    // Propagate it so the conversation scanner can retry without recording
    // this turn as already inspected.
    return parseCommitmentTaskDrafts(result.getOrThrow().text)
}

internal fun parseCommitmentTaskDrafts(raw: String): List<CommitmentTaskDraft> = runCatching {
    val array = decodeMemoryResponseArray(raw)
    buildList {
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val action = item.optString("action").trim().lowercase()
            if (action !in setOf("create", "reschedule", "cancel", "complete", "review")) continue
            val goal = item.optString("goal").trim()
            if (goal.isBlank() && action == "create") continue
            val dueText = item.optString("dueAt").trim()
            // One malformed date must not discard other independent, valid
            // commitments from the very same paid interpretation. Preserve
            // this obligation as pending clarification, NEVER invent a time.
            val dueAt = dueText.takeIf(String::isNotBlank)
                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val invalidDueAt = dueText.isNotBlank() && dueAt == null
            val proposedRoute = item.optString("deliveryAction").trim()
            // Wake delivery validation is only relevant to a concrete timed alarm.
            // A general long-term supervision goal may include "起床" without
            // promising a particular audible callback. Its future individual
            // actions are chosen by the character, not fabricated here.
            val timedWake = dueAt != null &&
                Regex("叫醒|叫我|喊我|起床|醒来|wake|睡醒", RegexOption.IGNORE_CASE)
                    .containsMatchIn(goal + " " + item.optString("completionCondition"))
            val wakeMethodMissing = action == "create" && timedWake &&
                proposedRoute !in setOf("start_call", "alarm_only")
            val proof = item.optJSONArray("completionEvidenceIds")?.let { values ->
                buildList { for (i in 0 until values.length()) values.optString(i).trim().takeIf(String::isNotBlank)?.let(::add) }
            }.orEmpty()
            val steps = item.optJSONArray("steps")?.let { values ->
                buildList { for (i in 0 until values.length()) values.optString(i).trim().takeIf(String::isNotBlank)?.let(::add) }
            }.orEmpty()
            add(
                CommitmentTaskDraft(
                    action = action,
                    goal = goal,
                    dueAt = dueAt,
                    timezone = item.optString("timezone").trim().takeIf(String::isNotBlank),
                    completionCondition = item.optString("completionCondition").trim(),
                    verificationMode = item.optString("verificationMode")
                        .takeIf { it in setOf("outcome", "delivery", "subjective_report") },
                    completionEvidenceIds = proof,
                    verificationReason = item.optString("verificationReason").trim(),
                    steps = steps,
                    deliveryAction = proposedRoute.takeIf {
                        it in setOf("start_call", "alarm_only", "send_private_message")
                    } ?: if (action == "create" && !timedWake) "send_private_message" else "",
                    promiseKind = item.optString("promiseKind").trim().takeIf(String::isNotBlank),
                    repeat = item.optString("repeat").takeIf { it in setOf("once", "daily") },
                    permanent = item.optBoolean("permanent", false),
                    needsClarification = invalidDueAt || wakeMethodMissing ||
                        item.optBoolean("needsClarification",
                            dueAt == null && action == "create" &&
                                item.optString("promiseKind") !in setOf("long_term_supervision", "responsibility")),
                    targetTaskId = item.optString("targetTaskId").trim().takeIf(String::isNotBlank),
                ),
            )
        }
    }
}.getOrElse { error ->
    throw IllegalArgumentException("承诺任务提取结果不是有效的 JSON 数组，保留重试机会", error)
}

/** Legacy wording detector, retained for compatibility. It no longer creates or schedules actions. */
internal fun detectSelfPromisedCall(text: String): Boolean {
    val clean = text.replace(Regex("\\s+"), "").take(700)
    if (clean.isBlank()) return false
    if (listOf("不打电话", "不会打电话", "不想打电话", "不要打电话", "别打电话").any(clean::contains)) return false
    if (listOf("要不要我打", "要我打电话吗", "想不想我打", "可以给你打电话吗").any(clean::contains)) return false
    val future = listOf("我等会", "我待会", "我一会", "我过会", "我稍后",
        "我晚点", "我会", "我来", "我给你", "等会我", "待会我", "一会我", "过会我").any(clean::contains)
    val call = listOf("打电话", "打个电话", "来电话", "打给你", "给你打", "拨电话", "电话催", "电话叫").any(clean::contains)
    return future && call
}


/** Preserve every model-selected field when the slow compatibility scanner hands
 * structured decisions to the SAME typed runtime as the foreground planner. */
internal fun commitmentDraftsToChanges(drafts: List<CommitmentTaskDraft>): JSONArray = JSONArray().apply {
    drafts.forEach { draft ->
        put(org.json.JSONObject().apply {
            put("action", draft.action)
            put("goal", draft.goal)
            put("targetTaskId", draft.targetTaskId.orEmpty())
            put("dueAt", draft.dueAt?.toString().orEmpty())
            put("timezone", draft.timezone.orEmpty())
            put("promiseKind", draft.promiseKind.orEmpty())
            put("needsClarification", draft.needsClarification)
            put("completionCondition", draft.completionCondition)
            put("verificationMode", draft.verificationMode.orEmpty())
            put("completionEvidenceIds", JSONArray(draft.completionEvidenceIds))
            put("verificationReason", draft.verificationReason)
            put("steps", JSONArray(draft.steps))
            put("deliveryAction", draft.deliveryAction)
            put("repeat", draft.repeat.orEmpty())
            put("permanent", draft.permanent)
        })
    }
}
