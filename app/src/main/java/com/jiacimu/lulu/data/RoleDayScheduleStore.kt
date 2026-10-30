package com.jiacimu.lulu.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

data class RoleDayPlan(
    val id: String,
    val characterId: String,
    val title: String,
    val intent: String,
    val dueAt: Instant,
    val createdAt: Instant,
    val status: String = "planned",
    val activityAction: String = "",
    val resolvedAt: Instant? = null,
    val resultEvidenceId: String = "",
    val resultSummary: String = "",
    val rescheduleCount: Int = 0,
    val repeat: String = "once",
    /** Reference into innerLife.motives, never another goal record. */
    val motiveId: String = "",
    val targetCharacterId: String = "",
    val targetResidentId: String = "",
    val locationCode: String = "",
    /** Requires a fresh decision. Explicit world activity/object IDs identify the planned outcome;
     * wording and other nonidentity arguments remain suggestions. */
    val activityArgs: String = "",
    /** Accepted long-term promise that motivated this specific time slot. */
    val commitmentTaskId: String = "",
    /** Assigned by the real autonomous entry point, never by model-authored args. */
    val selfInitiated: Boolean = false,
    /** Original unresolved concern; this is not another goal ledger. */
    val concernId: String = "",
    /** Exact model decision that created this plan; never inferred from matching prose or time. */
    val originEvidenceId: String = "",
    val originEvidenceRevision: Long = 0L,
    /** Calendar-atomic provenance of a resident due mutation, never model-supplied. */
    val lastMutationRequestId: String = "",
    /** The schedule worker handed the due item to the role; NOT evidence of execution. */
    val lastWakeHandoffAt: Instant? = null,
)

/** Intentions chosen by each role, never reconstructed from outgoing chat messages. */
object RoleDayScheduleStore {
    private const val PREFS = "lulu_role_day_plans_v1"
    private const val KEY = "plans"
    private val revisions = MutableStateFlow(0L)
    val changes = revisions

    private fun store(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun plans(context: Context, characterId: String): List<RoleDayPlan> =
        all(context).filter { it.characterId == characterId }.sortedBy { it.dueAt }

    private fun all(context: Context): List<RoleDayPlan> = runCatching {
        val raw = JSONArray(store(context).getString(KEY, "[]"))
        (0 until raw.length()).mapNotNull { index ->
            val o = raw.optJSONObject(index) ?: return@mapNotNull null
            runCatching {
                RoleDayPlan(o.getString("id"), o.getString("characterId"),
                    o.getString("title"), o.optString("intent"),
                    Instant.parse(o.getString("dueAt")), Instant.parse(o.getString("createdAt")),
                    o.optString("status", "planned"),
                    o.optString("activityAction"),
                    o.optString("resolvedAt").takeIf(String::isNotBlank)?.let(Instant::parse),
                    o.optString("resultEvidenceId"), o.optString("resultSummary"),
                    o.optInt("rescheduleCount", 0),
                    o.optString("repeat", "once"), o.optString("motiveId"),
                    o.optString("targetCharacterId"), o.optString("targetResidentId"),
                    o.optString("locationCode"), o.optString("activityArgs"),
                    o.optString("commitmentTaskId"), o.optBoolean("selfInitiated", false),
                    o.optString("concernId"), o.optString("originEvidenceId"),
                    o.optLong("originEvidenceRevision", 0L),
                    o.optString("lastMutationRequestId"),
                    o.optString("lastWakeHandoffAt").takeIf(String::isNotBlank)?.let(Instant::parse))
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    @Synchronized
    private fun persist(context: Context, records: List<RoleDayPlan>) {
        val json = JSONArray()
        records.forEach { p ->
            json.put(JSONObject().put("id", p.id).put("characterId", p.characterId)
                .put("title", p.title).put("intent", p.intent)
                .put("dueAt", p.dueAt.toString()).put("createdAt", p.createdAt.toString())
                .put("status", p.status)
                .put("activityAction", p.activityAction)
                .put("resolvedAt", p.resolvedAt?.toString().orEmpty())
                .put("resultEvidenceId", p.resultEvidenceId)
                .put("resultSummary", p.resultSummary)
                .put("rescheduleCount", p.rescheduleCount)
                .put("repeat", p.repeat).put("motiveId", p.motiveId)
                .put("targetCharacterId", p.targetCharacterId)
                .put("targetResidentId", p.targetResidentId)
                .put("locationCode", p.locationCode)
                .put("activityArgs", p.activityArgs)
                .put("commitmentTaskId", p.commitmentTaskId)
                .put("selfInitiated", p.selfInitiated).put("concernId", p.concernId)
                .put("originEvidenceId", p.originEvidenceId)
                .put("originEvidenceRevision", p.originEvidenceRevision)
                .put("lastMutationRequestId", p.lastMutationRequestId)
                .put("lastWakeHandoffAt", p.lastWakeHandoffAt?.toString().orEmpty()))
        }
        check(store(context).edit().putString(KEY, json.toString()).commit()) {
            "个人日程未能保存"
        }
        revisions.value += 1
    }

    /** Only real, independently verifiable actions can settle a plan. */
    val verifiableActions = setOf(
        "read_book", "play_solo_game", "digital_world_action", "write_journal", "publish_moment",
        "send_private_message", "send_character_message",
    )

    /** Every registered real tool can be planned; the live executor still checks actual permission.
     * Exclude lifecycle and planning operations rather than inventing a second tool registry. */
    private val scheduleControlTools = setOf(
        "plan_personal_activity", "reschedule_personal_activity", "cancel_personal_activity",
        "go_offline", "ring_in_call_wake", "cloud_task",
    )

    internal fun canSchedule(action: String): Boolean {
        val registered = CapabilityRegistry.scheduledActionName(action)
        return registered.isNotBlank() && registered !in scheduleControlTools &&
            CapabilityRegistry.canChoose(registered)
    }

    /** A repeated model action must not create another alarm for the same slot.
     * Different topics, times or activities remain independent choices. */
    internal fun duplicateOwnPlan(existing: RoleDayPlan, proposed: RoleDayPlan): Boolean {
        if (existing.characterId != proposed.characterId ||
            existing.status !in setOf("planned", "due") ||
            existing.activityAction != proposed.activityAction ||
            existing.repeat != proposed.repeat ||
            existing.activityArgs != proposed.activityArgs ||
            existing.motiveId != proposed.motiveId ||
            existing.concernId != proposed.concernId ||
            existing.commitmentTaskId != proposed.commitmentTaskId ||
            existing.targetCharacterId != proposed.targetCharacterId ||
            existing.targetResidentId != proposed.targetResidentId ||
            existing.locationCode != proposed.locationCode ||
            kotlin.math.abs(java.time.Duration.between(existing.dueAt, proposed.dueAt).seconds) > 60L
        ) return false
        val sameGoal = proposed.motiveId.isNotBlank() || proposed.concernId.isNotBlank() ||
            runCatching { JSONObject(proposed.activityArgs).optString("goalId") }.getOrDefault("").isNotBlank()
        return sameGoal || (existing.title.trim() == proposed.title.trim() &&
            existing.intent.trim() == proposed.intent.trim())
    }

    /** Preserve the role's selected wall-clock slot in UTC calendar days.
     * Skipped occurrences never create a backlog of automatic messages. */
    internal fun nextDailyDue(originalDue: Instant, now: Instant): Instant {
        val seconds = Duration.between(originalDue, now.plusSeconds(60)).seconds
        val days = (seconds / Duration.ofDays(1).seconds + 1L).coerceAtLeast(1L)
        return originalDue.plus(Duration.ofDays(days))
    }

    /** A lasting responsibility stays reviewable after one child step was planned.
     * Different study/rest/meal checks may be independently scheduled. */
    fun undatedCommitmentsToPlan(context: Context, characterId: String): List<CommitmentTask> =
        CommitmentTaskStore.active(characterId).filter(CommitmentTask::needsUndatedActionReview)


    /**
     * A single bridge from existing subjective motives and real opportunities to
     * executable self-selected time slots. Never generate a fixed day template:
     * the same personality decision that can act now can also decide later.
     */
    fun planningOpportunityContext(context: Context, characterId: String, now: Instant = Instant.now()): String {
        val tasks = undatedCommitmentsToPlan(context, characterId)
        val pendingPlans = plans(context, characterId).filter {
            it.status in setOf("planned", "due")
        }
        val linkedPlans = pendingPlans.filter { it.commitmentTaskId.isNotBlank() }
            .groupBy(RoleDayPlan::commitmentTaskId)
        val own = pendingPlans.filter { it.selfInitiated }.take(5)
        val motives = CharacterMotiveState.active(
            CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
        ).take(4)
        return buildString {
            appendLine("【自己的生活计划｜请与主人交办事项严格分开】")
            appendLine("主人要求的提醒、监督和约定属于对主人的责任；即使今天安排了几项，也不能代表你自己已经有想过的生活。自主生活从本人的长期心愿、真正在意的兴趣、真实经历、现实情绪、好奇或真实社交中生长，不以主人发布任务为前提。")
            if (motives.isNotEmpty()) {
                appendLine("现在仍在意的本人心愿（只提供候选，不是当天必须执行的任务）：")
                motives.forEach { m ->
                    appendLine("- motiveId=${m.optString("id")}；想做=${m.optString("aim").take(110)}；因为=${m.optString("why").take(100)}；下一步=${m.optString("nextStep").take(100)}")
                }
            }
            if (own.isNotEmpty()) {
                appendLine("已经由本人自主选择、仍待兑现的个人活动：")
                own.forEach {
                    appendLine("- planId=${it.id}；${it.title}；时间=${it.dueAt}；原因=${it.intent.take(100)}")
                }
                appendLine("可以继续过自己的生活、提早做别的事，或依据新想法改期/取消。不要每次唤醒都把已有计划复制一遍。")
            } else {
                appendLine("目前没有本人自主选择且待兑现的生活活动（用户提醒不计）。想做的事如果只停在念头里，之后不会到点发生；可在此轮本人决策中主动选择安排，或者确实决定独处与休息。")
            }
            appendLine("若真想稍后读书、社交、探索或游戏，由本人根据真实动机、时间与有效能力自己选择未来时刻，通过 plan_personal_activity 保存；已有 motiveId 可关联。")
            appendLine("同轮 steps 可以安排多件独立活动，也可以安静独处；已有近期个人日程会到点唤醒，不必另开定时打卡。")
            if (tasks.isNotEmpty()) {
                appendLine("【仍未安排下一步的主人责任｜需要本人主动复议，不是已设置的闹钟】")
                appendLine("现在本地时间=${now.atZone(java.time.ZoneId.systemDefault())}；这类约定没有实际系统闹钟，不可假称已安排。")
                tasks.take(6).forEach { task ->
                    appendLine("- commitmentTaskId=${task.id}；状态=${task.status}；约定=${task.goal}；创建=${task.createdAt}；完成条件=${task.completionCondition.take(130)}")
                    linkedPlans[task.id].orEmpty().take(4).forEach { plan ->
                        appendLine("  已安排 planId=${plan.id}；${plan.title.take(80)}；时间=${plan.dueAt}；动作=${plan.activityAction.ifBlank { "到时本人决定" }}；状态=${plan.status}")
                    }
                    val extra = linkedPlans[task.id].orEmpty().size - 4
                    if (extra > 0) appendLine("  同一责任还有${extra}项待执行安排，详见日程；不要重复建立。")
                }
                appendLine("你可以自己选下一步，不要把待确认一律理解为只能等用户：先判断确实缺少用户必须决定的条件，还是缺少你自己可决定的执行办法。前者选择合适时机询问或安排一次确认，绝不代用户编造答案；后者可自己选择未来时间，plan_personal_activity 关联 commitmentTaskId、title、intent、dueAt 与真实 activityAction，也可现在直接使用有权限的工具。一项长期责任可以分出学习、睡觉、吃饭等不同时刻的多项行动，每项独立回执；已经安排的相同步骤不要再建。行动成功不等于整个责任完成，还须观察实际结果。")
            }
            appendLine("现在可以执行、留到日后、改变心意或者暂时什么都不做；只有程序的实际工具和回执证明完成，不得用想象中的行动填日记或制造假经历。")
        }.trim()
    }

    fun plannedContext(context: Context, characterId: String, now: Instant = Instant.now()): String =
        plans(context, characterId).filter {
            it.status in setOf("planned", "due") &&
                it.dueAt.isAfter(now.minus(Duration.ofDays(1)))
        }.take(8).joinToString("\n") { plan ->
            "planId=${plan.id}，${plan.title}，${plan.dueAt}，" +
                "动机=${plan.intent}，重复=${plan.repeat}，" +
                "状态=${plan.status}，动作=${plan.activityAction}，" +
                "对象=${plan.targetCharacterId.ifBlank { plan.targetResidentId }}，地点=${plan.locationCode}，" +
                "关联意愿=${plan.motiveId.ifBlank { "无" }}，关联挂心=${plan.concernId.ifBlank { "无" }}，" +
                "关联约定=${plan.commitmentTaskId.ifBlank { "无" }}，" +
                "预选参数=${plan.activityArgs.take(360)}；" +
                "上次等待/改期的本人记录=${plan.resultSummary.ifBlank { "无" }}；" +
                "原时间版本 expectedDueAt=${plan.dueAt}。"
        }

    /** Already persisted personal commitments outrank a generic periodic sleep timer.
     * Overdue plans are owned by their existing worker; don't replay them every sweep. */
    fun nextPlannedWakeAt(context: Context, characterId: String, now: Instant): Instant? =
        plans(context, characterId).asSequence()
            .filter { it.status == "planned" && it.dueAt.isAfter(now) }
            .minOfOrNull { it.dueAt }

    /** A self-chosen ACTIVITY is an independent wake owner, not a user reminder.
     * An isolated far-future plan cannot indefinitely disable everyday awareness. */
    fun nextSelfDirectedWakeAt(context: Context, characterId: String, now: Instant): Instant? =
        plans(context, characterId).asSequence()
            .filter { it.selfInitiated && it.commitmentTaskId.isBlank() &&
                // Only executable intentions can replace fallback attention;
                // a vague calendar note alone cannot account for the day.
                it.activityAction.isNotBlank() &&
                it.status == "planned" && it.dueAt.isAfter(now) &&
                it.dueAt <= now.plus(Duration.ofDays(1)) }
            .minOfOrNull { it.dueAt }

    private fun refreshAutonomousWake(context: Context) {
        ProactivePerceptionScheduler.scheduleNextDue(context.applicationContext)
    }

    private fun scheduleWake(context: Context, plan: RoleDayPlan, now: Instant,
        replace: Boolean = true, appendToCurrentWork: Boolean = false) {
        val delay = Duration.between(now, plan.dueAt).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<RoleScheduleWakeWorker>()
            .setInputData(Data.Builder().putString("roleId", plan.characterId)
                .putString("planId", plan.id)
                .putString("dueAt", plan.dueAt.toString()).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "role-own-plan-${plan.id}",
            when {
                appendToCurrentWork -> ExistingWorkPolicy.APPEND_OR_REPLACE
                replace -> ExistingWorkPolicy.REPLACE
                else -> ExistingWorkPolicy.KEEP
            }, request)
    }

    /** A missed WorkManager delivery must remain a real due opportunity after process
     * death or a date rollover. Request one missing handoff, never replay the old
     * action or silently declare the promise fulfilled. The existing role decision
     * then chooses whether to act, defer, or cancel. */
    internal fun needsWakeRestoration(plan: RoleDayPlan, now: Instant): Boolean =
        if (plan.characterId.startsWith("resident:")) {
            plan.status == "planned" && plan.dueAt.isAfter(now)
        } else {
            plan.status == "planned" ||
                (plan.status == "due" && plan.lastWakeHandoffAt == null && !plan.dueAt.isAfter(now))
        }

    fun restorePending(context: Context, now: Instant = Instant.now()) {
        all(context).filter { plan ->
            needsWakeRestoration(plan, now) ||
                (plan.status in setOf("due", "completed") &&
                    plan.characterId.startsWith("resident:") &&
                    (ResidentDailyDecision.hasSavedDueToResume(context, plan) ||
                        ResidentDailyDecision.hasSafeDueRequestToRetry(context, plan)))
        }.forEach { plan ->
            runCatching { scheduleWake(context, plan, now, replace = false) }
                .onFailure { android.util.Log.w("LuluSchedule", "Role wake restoration failed", it) }
        }
    }

    @Synchronized
    fun arrange(context: Context, characterId: String, title: String, intent: String,
        dueAt: Instant, now: Instant = Instant.now(), activityAction: String = "",
        repeat: String = "once", motiveId: String = "",
        targetCharacterId: String = "", targetResidentId: String = "",
        locationCode: String = "", activityArgs: JSONObject = JSONObject(),
        commitmentTaskId: String = "", selfInitiated: Boolean = false,
        concernId: String = "", originEvidenceId: String = "",
        originEvidenceRevision: Long = 0L): RoleDayPlan {
        require(characterId.isNotBlank()) { "角色不能为空" }
        require(originEvidenceRevision >= 0L &&
            (originEvidenceId.isNotBlank() || originEvidenceRevision == 0L)) {
            "日程来源版本必须对应明确的原始证据"
        }
        require(title.trim().length in 2..100 && intent.trim().length in 3..300) {
            "日程需要具体想做的事与本人的动机"
        }
        require(dueAt.isAfter(now.plusSeconds(60))) { "日程需要明确的未来时间" }
        val action = CapabilityRegistry.scheduledActionName(activityAction)
        require(action.isBlank() || canSchedule(action)) { "日程动作不是已注册的可执行工具" }
        require(action.isNotBlank() || activityArgs.length() == 0) { "预选参数必须对应一个真实工具" }
        require(activityArgs.keys().asSequence().none { it == "planId" || it.startsWith("_") }) {
            "预选参数不能提前携带任务回执或执行身份"
        }
        require(repeat in setOf("once", "daily")) { "只支持单次或每日重复" }
        require(repeat != "daily" || action.isNotBlank()) { "每日计划需要明确真实执行动作" }
        val linkedCommitment = commitmentTaskId.trim()
        if (linkedCommitment.isNotBlank()) {
            require(CommitmentTaskStore.active(characterId).any {
                it.id == linkedCommitment && it.needsUndatedActionReview()
            }) { "只能关联本人已接受、没有定时器的持续约定" }
        }
        val linkedId = motiveId.trim()
        val linkedConcern = concernId.trim()
        if (linkedConcern.isNotBlank()) {
            require(CharacterOpenConcernRuntime.active(
                CharacterInnerLifeStore.snapshot(characterId).optJSONArray("openConcerns"), now,
            ).any { it.optString("id") == linkedConcern }) {
                "关联心事必须是本人真实仍在关注的原挂心ID"
            }
        }
        val targetRole = targetCharacterId.trim()
        val targetNative = targetResidentId.trim()
        val place = locationCode.trim()
        if (targetRole.isNotBlank() || targetNative.isNotBlank() || place.isNotBlank()) {
            require(DigitalLifeProfileStore.isEnabled(characterId)) {
                "人物或地点日程需要真实的数字生活"
            }
        }
        if (targetRole.isNotBlank()) {
            require(targetRole != characterId &&
                MigratedDomainStores.characters.settings.value[targetRole] != null) {
                "日程关联的数字人物必须真实存在"
            }
        }
        if (targetNative.isNotBlank()) {
            require(DigitalWorldExpansionStore.residents().any { it.id == targetNative }) {
                "日程关联的居民必须真实存在"
            }
        }
        if (place.isNotBlank()) {
            require(DigitalWorldPublicPlaces.all.any { it.code == place }) {
                "日程地点必须是已登记的公共地点"
            }
        }
        if (action == "send_character_message") {
            require(targetRole.isNotBlank()) { "安排给数字人物发信时需指定真实收件人" }
        }
        if (linkedId.isNotBlank()) {
            require(CharacterMotiveProgress.resolveMotiveId(
                CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
                linkedId, "", "") == linkedId) { "关联意愿必须属于本人且尚未暂停或放下" }
        }
        val existing = all(context)
        // A user-facing promise with an alarm is the ONE executable schedule.
        // Do not also queue a WorkManager self-plan for the same commitment.
        // This applies to one-off study prompts as well as daily bedtime calls.
        val proposed = RoleDayPlan(
            id = "", characterId = characterId, title = title.trim(), intent = intent.trim(),
            dueAt = dueAt, createdAt = now, activityAction = action, repeat = repeat,
            motiveId = linkedId, activityArgs = activityArgs.toString(),
            targetCharacterId = targetRole, targetResidentId = targetNative,
            locationCode = place, commitmentTaskId = linkedCommitment,
            concernId = linkedConcern,
            originEvidenceId = originEvidenceId, originEvidenceRevision = originEvidenceRevision,
        )
        require(CommitmentTaskStore.snapshot(characterId).none { task ->
            task.status in setOf(CommitmentTaskStatus.Scheduled, CommitmentTaskStatus.Running,
                CommitmentTaskStatus.WaitingForFeedback) &&
                duplicatesScheduledReminder(proposed, task)
        }) { "同一件定时提醒已有约定，已保留原任务；无需重复安排个人日程" }
        // Replayed identical choice keeps its original persistent work/alarm.
        existing.firstOrNull { duplicateOwnPlan(it, proposed) }?.let { return it }
        // Do not lock a long-term commitment after one future step. Identical
        // decisions are deduplicated above; different slots/actions are valid.
        // No arbitrary per-person plan quota: an ongoing life may have many distinct intentions.
        val plan = RoleDayPlan(UUID.randomUUID().toString(), characterId,
            title.trim(), intent.trim(), dueAt, now, activityAction = action,
            repeat = repeat, motiveId = linkedId, targetCharacterId = targetRole,
            targetResidentId = targetNative, locationCode = place,
            activityArgs = activityArgs.toString(),
            commitmentTaskId = linkedCommitment, concernId = linkedConcern,
            selfInitiated = selfInitiated && linkedCommitment.isBlank(),
            originEvidenceId = originEvidenceId, originEvidenceRevision = originEvidenceRevision)
        persist(context, existing + plan)
        try {
            scheduleWake(context, plan, now)
        } catch (failure: Exception) {
            persist(context, existing)
            throw failure
        }
        refreshAutonomousWake(context)
        return plan
    }

    @Synchronized
    fun cancel(context: Context, characterId: String, planId: String, reason: String = "",
        now: Instant = Instant.now(), retainRunningWake: Boolean = false,
        expectedDueAt: Instant? = null, mutationRequestId: String = ""): Boolean {
        val records = all(context)
        val target = records.firstOrNull { it.characterId == characterId && it.id == planId &&
            it.status in setOf("planned", "due") } ?: return false
        // A model answer from an earlier wake cannot cancel a later user edit.
        if (expectedDueAt != null && target.dueAt != expectedDueAt) return false
        persist(context, records.map {
            if (it.id == target.id) it.copy(status = "cancelled", resolvedAt = now,
                resultSummary = reason.trim().take(180),
                lastMutationRequestId = mutationRequestId) else it
        })
        // The resident's own due worker must be allowed to finish persisting
        // the SAME model decision and independent receipts. Normal user-driven
        // cancellation still revokes its pending worker as before.
        if (!retainRunningWake) {
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("role-own-plan-$planId")
        }
        refreshAutonomousWake(context)
        return true
    }

    /** Revoke unfulfilled child plans when their parent responsibility is
     * cancelled, completed or amended. Historical actions and receipts survive. */
    @Synchronized
    fun cancelPendingLinkedCommitmentPlans(context: Context, characterId: String,
        commitmentTaskId: String, reason: String, now: Instant = Instant.now()) {
        if (commitmentTaskId.isBlank()) return
        val records = all(context)
        val pendingIds = records.filter { plan ->
            plan.characterId == characterId && plan.commitmentTaskId == commitmentTaskId &&
                plan.status in setOf("planned", "due")
        }.map(RoleDayPlan::id).toSet()
        if (pendingIds.isEmpty()) return
        persist(context, records.map { plan ->
            if (plan.id in pendingIds) plan.copy(
                status = "cancelled", resolvedAt = now, resultSummary = reason.take(180),
            ) else plan
        })
        pendingIds.forEach { planId ->
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("role-own-plan-$planId")
        }
        refreshAutonomousWake(context)
    }

    /** Delete only the plan and its wake. Raw conversation and tool evidence remain intact. */
    @Synchronized
    fun remove(context: Context, characterId: String, planId: String): Boolean {
        val old = all(context)
        if (old.none { it.id == planId && it.characterId == characterId }) return false
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("role-own-plan-$planId")
        persist(context, old.filterNot { it.id == planId && it.characterId == characterId })
        refreshAutonomousWake(context)
        return true
    }

    @Synchronized
    fun removeLinkedCommitmentPlans(context: Context, characterId: String, commitmentTaskId: String) {
        if (commitmentTaskId.isBlank()) return
        val linked = all(context).filter { it.characterId == characterId &&
            it.commitmentTaskId == commitmentTaskId }
        if (linked.isEmpty()) return
        linked.forEach { WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("role-own-plan-${it.id}") }
        persist(context, all(context).filterNot { it.characterId == characterId &&
            it.commitmentTaskId == commitmentTaskId })
        refreshAutonomousWake(context)
    }

    /**
     * Upgrade from older versions: user-facing reminders used to be written to
     * BOTH the promise alarm store and the personal-plan WorkManager store.
     * Keep the already armed promise and remove only the duplicate worker.
     * Never remove the character's own reading/social/life plans.
     */
    @Synchronized
    fun mergeMatchingDailyCallPlans(context: Context) {
        val candidates = all(context).filter { it.status in setOf("planned", "due") }
        candidates.forEach { plan ->
            val task = CommitmentTaskStore.snapshot(plan.characterId).firstOrNull { c ->
                isAuthoritativeScheduledReminder(c) && duplicatesScheduledReminder(plan, c)
            } ?: return@forEach
            // An older pair may have put "daily" on the plan and "once"
            // on the promise; the single surviving alarm inherits recurrence.
            if (plan.repeat == "daily" && task.repeat != "daily") {
                CommitmentTaskStore.update(task.id) { current ->
                    if (current.status != CommitmentTaskStatus.Scheduled) current
                    else current.copy(repeat = "daily")
                }
            }
            remove(context, plan.characterId, plan.id)
        }
    }

    @Synchronized
    fun reschedule(context: Context, characterId: String, planId: String,
        dueAt: Instant, reason: String, now: Instant = Instant.now(),
        appendToCurrentWork: Boolean = false,
        expectedDueAt: Instant? = null, mutationRequestId: String = ""): RoleDayPlan {
        require(dueAt.isAfter(now.plusSeconds(60))) { "改期必须是未来时间" }
        val previous = all(context)
        val original = previous.firstOrNull { it.characterId == characterId && it.id == planId &&
            it.status in setOf("planned", "due") } ?: error("没有可改期的本人日程")
        require(expectedDueAt == null || original.dueAt == expectedDueAt) {
            "原日程已被其他决定修改，禁止旧模型结果覆盖新时间"
        }
        val revised = original.copy(dueAt = dueAt, status = "planned",
            resultSummary = reason.trim().take(180), rescheduleCount = original.rescheduleCount + 1,
            lastMutationRequestId = mutationRequestId)
        persist(context, previous.map { if (it.id == planId) revised else it })
        try {
            scheduleWake(context, revised, now, appendToCurrentWork = appendToCurrentWork)
        } catch (failure: Exception) {
            persist(context, previous)
            runCatching { scheduleWake(context, original, now) }
            throw failure
        }
        refreshAutonomousWake(context)
        return revised
    }

    /** Pure eligibility gate: a plan cannot be 'completed' by prose, another role,
     * the wrong action, or a tool result before its scheduled time. */
    internal fun canComplete(plan: RoleDayPlan, characterId: String, action: String,
        evidenceId: String, now: Instant, args: JSONObject? = null): Boolean =
        plan.characterId == characterId && evidenceId.isNotBlank() &&
            (action in verifiableActions ||
                (plan.activityAction == action && canSchedule(action))) &&
            (plan.activityAction.isBlank() || plan.activityAction == action) &&
            plan.status in setOf("due", "planned") && !plan.dueAt.isAfter(now) &&
            matchesChosenWorldActivity(plan, action, args) &&
            (plan.targetCharacterId.isBlank() || when (action) {
                "send_character_message", "digital_world_action" ->
                    args?.optString("targetCharacterId") == plan.targetCharacterId
                else -> false
            }) &&
            (plan.targetResidentId.isBlank() || (action == "digital_world_action" &&
                args?.optString("worldAction") == "interact_resident" &&
                args?.optString("itemId") == plan.targetResidentId)) &&
            (plan.locationCode.isBlank() || action != "digital_world_action" ||
                args?.optString("worldAction") != "visit_public_place" ||
                (args?.optString("locationCode").orEmpty()
                    .ifBlank { args?.optString("location").orEmpty() } == plan.locationCode))

    /** Compare only explicit structured activity identities, never infer a goal from its title.
     * A scheduled discussion/continuation cannot be completed by greeting the same person. */
    private fun matchesChosenWorldActivity(plan: RoleDayPlan, action: String, args: JSONObject?): Boolean {
        if (action != "digital_world_action" || plan.activityArgs.isBlank()) return true
        val selected = runCatching { JSONObject(plan.activityArgs) }.getOrNull() ?: return false
        return listOf("worldAction", "activityId", "incidentId", "itemId", "targetCharacterId")
            .all { key -> !selected.has(key) || selected.optString(key).isBlank() ||
                args?.optString(key) == selected.optString(key) }
    }

    /** A chosen encounter venue is an execution fact, not prose in the title.
     * Preflight uses the live world. Receipt recovery uses the actual committed
     * meeting, so walking away afterward cannot invalidate a successful visit.
     */
    private fun matchesEncounterVenue(plan: RoleDayPlan, action: String, args: JSONObject?,
        evidenceId: String? = null, now: Instant): Boolean {
        if (plan.locationCode.isBlank() || action != "digital_world_action" ||
            normalizeWorldAction(args?.optString("worldAction").orEmpty()) != "interact_resident") return true
        if (evidenceId == null) return DigitalWorldStore.locationOf(plan.characterId) == plan.locationCode
        val event = SharedExperienceTimeline.eventsByIds(plan.characterId, listOf(evidenceId))
            .singleOrNull() ?: return false
        if (event.source != "meeting" || event.evidenceKind != EventEvidenceKind.ToolResult ||
            event.revision != 1L || event.sessionId.isBlank() ||
            event.id != "meeting-${event.sessionId}-resident-summary-viewer-${plan.characterId}" ||
            event.occurredAt.isBefore(plan.dueAt) || event.occurredAt.isAfter(now)) return false
        val meeting = DigitalWorldStore.state.value.meetings.firstOrNull {
            it.id == event.sessionId && it.reality == MeetingReality.DIGITAL_WORLD && it.endedAt != null
        } ?: return false
        if (plan.targetCharacterId.isNotBlank() && plan.targetCharacterId !in meeting.participantIds) return false
        val nativeId = "resident:${args?.optString("itemId").orEmpty()}"
        if (listOf(plan.characterId, nativeId).any { participant ->
                participant !in meeting.participantIds || meeting.turns.none { it.speakerId == participant }
            }) return false
        // A display name/current registry cannot reconstruct a historical
        // venue identity. Legacy records without the executor's code remain
        // readable but cannot newly prove this explicit appointment completed.
        return meeting.locationCode.isNotBlank() && meeting.locationCode == plan.locationCode
    }

    /** Do not perform an action for a revoked wish, wrong target or old appointment. */
    fun canBegin(context: Context, characterId: String, planId: String, action: String,
        args: JSONObject, now: Instant = Instant.now(),
        expectedDueAt: Instant? = null): Boolean {
        val plan = plans(context, characterId).firstOrNull { it.id == planId } ?: return false
        if (expectedDueAt != null && plan.dueAt != expectedDueAt) return false
        if (!canComplete(plan, characterId, action, "pending-action", now, args) ||
            !matchesEncounterVenue(plan, action, args, now = now)) return false
        if (plan.commitmentTaskId.isNotBlank() && CommitmentTaskStore.active(characterId).none {
                it.id == plan.commitmentTaskId &&
                    it.needsUndatedActionReview()
            }) return false
        return plan.motiveId.isBlank() || CharacterMotiveProgress.resolveMotiveId(
            CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
            plan.motiveId, "", "") == plan.motiveId
    }

    /** Status projection only; the execution evidence remains in the canonical raw timeline. */
    @Synchronized
    fun completeFromReceipt(context: Context, characterId: String, planId: String,
        action: String, evidenceId: String, summary: String, now: Instant = Instant.now(),
        args: JSONObject? = null): Boolean {
        // canComplete is the single authoritative action/owner/time/target gate.
        // Do not silently discard verified receipts from newly schedulable tools.
        if (planId.isBlank() || evidenceId.isBlank()) return false
        val previous = all(context)
        val plan = previous.firstOrNull { it.id == planId &&
            canComplete(it, characterId, action, evidenceId, now, args) &&
                matchesEncounterVenue(it, action, args, evidenceId, now) &&
                (it.commitmentTaskId.isBlank() || CommitmentTaskStore.active(characterId).any { task ->
                    task.id == it.commitmentTaskId && task.needsUndatedActionReview()
                }) &&
                (it.motiveId.isBlank() || CharacterMotiveProgress.resolveMotiveId(
                    CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
                    it.motiveId, "", "") == it.motiveId) } ?: return false
        // A daily plan is a single durable intention, not a new duplicate every morning.
        // Evidence always remains in the authoritative timeline; the next occurrence
        // cannot be completed using the previous day's receipt.
        val follow = if (plan.repeat == "daily") {
            val nextAt = nextDailyDue(plan.dueAt, now)
            plan.copy(status = "planned", dueAt = nextAt, resolvedAt = now,
                resultEvidenceId = evidenceId, resultSummary = summary.take(180))
        } else plan.copy(status = "completed",
            resolvedAt = now, resultEvidenceId = evidenceId, resultSummary = summary.take(180))
        persist(context, previous.map { if (it.id == plan.id) follow else it })
        if (plan.repeat == "daily") {
            try {
                scheduleWake(context, follow, now)
            } catch (failure: Exception) {
                // Never falsely count a due reminder as scheduled if the OS rejects it.
                persist(context, previous)
                throw failure
            }
        } else if (!(plan.characterId.startsWith("resident:") &&
                ResidentDailyDecision.hasSavedDueToResume(context, follow))) {
            // A resident's already-saved execute decision may still have side
            // steps after this primary receipt. Cancelling the unique work here
            // cancels its own Worker before those receipts can be committed.
            WorkManager.getInstance(context.applicationContext)
                .cancelUniqueWork("role-own-plan-$planId")
        }
        refreshAutonomousWake(context)
        return true
    }

    @Synchronized
    fun markDue(context: Context, characterId: String, planId: String,
        expectedDueAt: Instant? = null): RoleDayPlan? {
        val existing = all(context)
        val target = existing.firstOrNull {
            it.characterId == characterId && it.id == planId && it.status in setOf("planned", "due") &&
                (expectedDueAt == null || it.dueAt == expectedDueAt)
        } ?: return null
        if (target.status == "planned") {
            // The planned worker now owns this event. Do not enqueue a second
            // ordinary due tick before its autonomous decision is processed.
            persist(context, existing.map { if (it.id == planId) it.copy(status = "due") else it })
        }
        return target.copy(status = "due")
    }

    /** Record only a successful dispatch into the existing character wake pipeline.
     * Kept on the same plan so reopening the app cannot repeatedly create paid
     * reconsiderations. A later action still requires its own verified receipt. */
    @Synchronized
    internal fun recordWakeHandoff(context: Context, characterId: String, planId: String,
        expectedDueAt: Instant, at: Instant = Instant.now()): Boolean {
        val records = all(context)
        val original = records.firstOrNull {
            it.characterId == characterId && it.id == planId &&
                it.status == "due" && it.dueAt == expectedDueAt
        } ?: return false
        if (original.lastWakeHandoffAt != null) return true
        persist(context, records.map { item ->
            if (item.id == planId && item.characterId == characterId)
                item.copy(lastWakeHandoffAt = at) else item
        })
        return true
    }

    /** A due plan is not a completed activity. Only wake the original role's decision loop. */
    fun dueContext(context: Context, characterId: String, now: Instant = Instant.now()): String =
        plans(context, characterId).filter { it.status == "due" ||
            (it.status == "planned" && !it.dueAt.isAfter(now)) }
            .joinToString("\n") {
                "待处理的本人日程 planId=${it.id}；原定活动：${it.title}；本人缘由：${it.intent}；时间：${it.dueAt}；" +
                    "关联意愿：${it.motiveId.ifBlank { "独立日程" }}；关联挂心：${it.concernId.ifBlank { "无" }}；" +
                    "关联约定：${it.commitmentTaskId.ifBlank { "无" }}；" +
                    "相关人物：${it.targetCharacterId.ifBlank { it.targetResidentId.ifBlank { "无" } }}；" +
                    "相关地点：${it.locationCode.ifBlank { "未指定" }}；" +
                    (if (it.motiveId.isNotBlank() &&
                        CharacterMotiveProgress.resolveMotiveId(
                            CharacterInnerLifeStore.snapshot(characterId).optJSONArray("motives"),
                            it.motiveId, "", "") != it.motiveId)
                        "关联意愿已暂停或放下：先重新考虑是否执行、改期或取消；" else "") +
                    "对应动作：${it.activityAction.ifBlank { "按具体活动选择真实执行工具" }}；" +
                    "上次选定的参数：${it.activityArgs.ifBlank { "{}" }.take(1200)}。" +
                    "唤醒交接：${it.lastWakeHandoffAt?.let { at -> "已于$at 请求本人决策；尚不代表实际执行" } ?: "尚无成功交接回执"}。" +
                    "重新核实场景与用户意愿，可按原参数执行，也可适当调整；不自动调用。" +
                    (if (it.activityAction == "digital_world_action")
                        "原参数明确给出的 worldAction/activityId/incidentId/itemId/targetCharacterId 标识本项活动，" +
                            "不能用打招呼或另一件事的回执完成它。若决定换活动，取消原项并另行安排；" +
                            "文字内容等非身份参数可由本人调整。出发、到场等准备动作不附本项 planId。" else "") +
                    (if (it.locationCode.isNotBlank() && it.activityAction == "digital_world_action")
                        "与居民当面交流须先在约定地点；回执按真实会面地点核验，不能以参数声称到场。若决定换地点，取消原项并另行安排。" else "") +
                    "想做就执行真实工具并把 planId 放入同一次最终动作 args；只有成功回执才算完成。" +
                    "如对方已经在学习、正在休息或不需打扰，可改期或取消，绝不照表打卡。" +
                    "改期调用 reschedule_personal_activity，放弃调用 cancel_personal_activity；" +
                    "仅想一想、聊天、失败、没有证据都不能冒充完成。"
            }

    @Synchronized
    fun clearCharacter(context: Context, characterId: String) {
        val targets = all(context).filter { it.characterId == characterId }
        targets.forEach { WorkManager.getInstance(context).cancelUniqueWork("role-own-plan-${it.id}") }
        persist(context, all(context).filterNot { it.characterId == characterId })
        refreshAutonomousWake(context)
    }
}

class RoleScheduleWakeWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val role = inputData.getString("roleId").orEmpty()
        val planId = inputData.getString("planId").orEmpty()
        if (role.isBlank() || planId.isBlank()) return Result.failure()
        // Older installed workers have no dueAt field. New workers carry the
        // exact appointment version so an obsolete wake never claims its replacement.
        val encodedDueAt = inputData.getString("dueAt").orEmpty()
        val expectedDueAt = encodedDueAt.takeIf { it.isNotBlank() }?.let {
            runCatching { Instant.parse(it) }.getOrNull()
        }
        if (encodedDueAt.isNotBlank() && expectedDueAt == null) return Result.failure()
        val current = RoleDayScheduleStore.plans(applicationContext, role).firstOrNull { it.id == planId }
            ?: return Result.success()
        fun canResume(p: RoleDayPlan): Boolean =
            p.status in setOf("planned", "due") ||
                (role.startsWith("resident:") && p.status == "completed" &&
                    ResidentDailyDecision.hasSavedDueToResume(applicationContext, p))
        if (!canResume(current) || (expectedDueAt != null && current.dueAt != expectedDueAt))
            return Result.success()
        // A cold WorkManager process has not loaded CommitmentTaskStore yet.
        // Read the durable alarm authority BEFORE deciding to wake the role.
        if (runCatching {
                initializeBackgroundRuntime(applicationContext)
                ProactivePerceptionRuntime.initialize(applicationContext)
            }.isFailure) return Result.retry()
        val latest = RoleDayScheduleStore.plans(applicationContext, role)
            .firstOrNull { it.id == planId } ?: return Result.success()
        if (!canResume(latest) || (expectedDueAt != null && latest.dueAt != expectedDueAt))
            return Result.success()
        // A stale WorkManager job may survive cancellation, backup restore or
        // racing parent edits. Never wake a role for a revoked child action.
        if (latest.commitmentTaskId.isNotBlank() &&
            CommitmentTaskStore.active(role).none {
                it.id == latest.commitmentTaskId && it.needsUndatedActionReview()
            }) {
            RoleDayScheduleStore.cancel(applicationContext, role, planId,
                "原约定已撤销或改为定时任务")
            return Result.success()
        }
        val covered = CommitmentTaskStore.snapshot(role).any {
            isAuthoritativeScheduledReminder(it) && duplicatesScheduledReminder(latest, it)
        }
        if (covered) {
            RoleDayScheduleStore.mergeMatchingDailyCallPlans(applicationContext)
            return Result.success()
        }
        if (Instant.now().isBefore(latest.dueAt)) return Result.retry()
        val plan = if (latest.status == "completed") latest
            else RoleDayScheduleStore.markDue(applicationContext, role, planId,
                expectedDueAt = expectedDueAt)
                ?: return Result.success()
        if (role.startsWith("resident:")) {
            // A native resident has no ordinary user-facing private chat. Route its
            // appointment directly to the resident's inexpensive model decision,
            // rather than waking a non-existent chat conversation.
            val completed = try {
                ResidentDailyDecision.runDuePlan(applicationContext, plan)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (failure: Exception) {
                com.jiacimu.lulu.LuluRepositories.performance.recordError(
                    source = "数字世界居民日程", title = "居民行程到点执行失败",
                    message = failure.message.orEmpty().take(180))
                false
            }
            // Only an already-saved model choice is safe for a bounded retry.
            // A lost/uncertain response must never cause another paid request.
            if (!completed && (ResidentDailyDecision.hasSavedDueToResume(applicationContext, plan) ||
                    ResidentDailyDecision.hasSafeDueRequestToRetry(applicationContext, plan)))
                return Result.retry()
            return Result.success()
        }
        return runCatching {
            ProactivePerceptionScheduler.scheduleSelfPlanned(applicationContext, role, plan.title)
            // Only the *wake request* was submitted; do not mark the plan
            // completed or claim the selected tool has run.
            RoleDayScheduleStore.recordWakeHandoff(
                applicationContext, role, plan.id, plan.dueAt)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}

/** The promise actually owns a native alarm or one already claimed execution. */
internal fun isAuthoritativeScheduledReminder(task: CommitmentTask): Boolean = when (task.status) {
    CommitmentTaskStatus.Scheduled -> !task.linkedAlarmId.isNullOrBlank()
    CommitmentTaskStatus.Running -> !task.executionToken.isNullOrBlank()
    CommitmentTaskStatus.WaitingForFeedback -> task.lastAlarmReceivedAt != null
    else -> false
}

/**
 * The same owner-directed reminder must not create two independently waking
 * schedules. A self-directed activity or a different topic stays independent.
 * The promise is the executable authority; its calendar card is a projection.
 */
internal fun duplicatesScheduledReminder(plan: RoleDayPlan, task: CommitmentTask): Boolean {
    if (plan.characterId != task.characterId ||
        task.deliveryAction !in setOf("send_private_message", "alarm_only", "start_call")) return false
    // An owner-facing check is one appointment even when the role independently
    // chose screen observation (or left the future action open). The native
    // commitment wakes the SAME character decision, which can then choose
    // allowed observation and dialogue in that turn. A distinct life activity,
    // recipient or place must keep its own independent schedule.
    if (plan.activityAction !in setOf(
            "", "send_private_message", "summon_lulu", "start_call", "capture_screen",
        ) || plan.motiveId.isNotBlank() ||
        plan.targetCharacterId.isNotBlank() || plan.targetResidentId.isNotBlank() ||
        plan.locationCode.isNotBlank()) return false
    val deadline = task.dueAt ?: return false
    val zone = task.timezone?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }
        ?: java.time.ZoneId.systemDefault()
    val a = plan.dueAt.atZone(zone)
    val b = deadline.atZone(zone)
    val timeMatches = kotlin.math.abs(java.time.Duration.between(
        a.toLocalTime(), b.toLocalTime()).seconds) <= 60L
    if (!timeMatches) return false
    // A repeated reminder owns the local wall-clock; otherwise require the
    // actual SAME calendar date, not just another week's 10:30.
    if (plan.repeat != "daily" && task.repeat != "daily" &&
        a.toLocalDate() != b.toLocalDate()) return false
    val groups = listOf(
        listOf("学习", "复习", "背书", "背诵", "做题", "作业", "刷题"),
        listOf("睡觉", "睡前", "入睡", "哄睡", "晚安", "早睡", "休息"),
        listOf("起床", "叫醒", "喊醒", "晨起"),
        listOf("回来", "返回露露机", "叫回来"),
    )
    val planText = plan.title + plan.intent
    val taskText = task.goal + task.completionCondition
    return groups.any { words ->
        words.any(planText::contains) && words.any(taskText::contains)
    }
}
