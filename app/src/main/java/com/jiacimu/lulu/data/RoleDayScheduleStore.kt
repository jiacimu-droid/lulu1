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
    /** Planned tool arguments are a suggestion, not permission to execute without a new decision. */
    val activityArgs: String = "",
    /** Accepted long-term promise that motivated this specific time slot. */
    val commitmentTaskId: String = "",
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
                    o.optString("commitmentTaskId"))
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
                .put("commitmentTaskId", p.commitmentTaskId))
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
        "go_offline", "write_journal", "ring_in_call_wake", "cloud_task",
    )

    internal fun canSchedule(action: String): Boolean =
        action.isNotBlank() && action !in scheduleControlTools &&
            CapabilityRegistry.find(action) != null

    /** Preserve the role's selected wall-clock slot in UTC calendar days.
     * Skipped occurrences never create a backlog of automatic messages. */
    internal fun nextDailyDue(originalDue: Instant, now: Instant): Instant {
        val seconds = Duration.between(originalDue, now.plusSeconds(60)).seconds
        val days = (seconds / Duration.ofDays(1).seconds + 1L).coerceAtLeast(1L)
        return originalDue.plus(Duration.ofDays(days))
    }

    /** An undated ongoing promise has NO alarm. Only its owner may choose a dated action. */
    fun undatedCommitmentsToPlan(context: Context, characterId: String): List<CommitmentTask> {
        val linked = plans(context, characterId)
            .filter { it.status in setOf("planned", "due") }
            .map { it.commitmentTaskId }.filter(String::isNotBlank).toSet()
        return CommitmentTaskStore.active(characterId).filter { task ->
            task.dueAt == null && task.status == CommitmentTaskStatus.WaitingForFeedback &&
                task.id !in linked
        }
    }

    fun planningOpportunityContext(context: Context, characterId: String, now: Instant = Instant.now()): String {
        val tasks = undatedCommitmentsToPlan(context, characterId)
        if (tasks.isEmpty()) return ""
        return buildString {
            appendLine("【已接受但未安排下次行动时间的持续约定】")
            appendLine("当前本地时间=${now.atZone(java.time.ZoneId.systemDefault())}。以下约定没有闹钟、不会自动兑现；旧约定不能因为日期变化就算作次日新日程。")
            tasks.take(6).forEach {
                appendLine("- commitmentTaskId=${it.id}；约定=${it.goal}；创建=${it.createdAt}；完成条件=${it.completionCondition.take(130)}")
            }
            appendLine("如果自己确实想今晚监督休息、今日督促学习或在合适时刻主动履约，请参考主人真实作息偏好和本地时间，自己选未来日期时刻、选已注册可执行工具，立刻用 plan_personal_activity 保存，带上 commitmentTaskId、title、intent、dueAt、activityAction、activityArgs。无需主人告诉你几点。")
            appendLine("这不是强制每天提醒。可以选择暂缓或不干扰；但没有保存定时动作就不能声称已经安排。已过去的时段不许暗中滚动到明天；需要新的执行时间时由你显式选择、真实改期。")
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
                "关联意愿=${plan.motiveId.ifBlank { "无" }}，" +
                "关联约定=${plan.commitmentTaskId.ifBlank { "无" }}，" +
                "预选参数=${plan.activityArgs.take(360)}。"
        }

    /** Already persisted personal commitments outrank a generic periodic sleep timer.
     * Overdue plans are owned by their existing worker; don't replay them every sweep. */
    fun nextPlannedWakeAt(context: Context, characterId: String, now: Instant): Instant? =
        plans(context, characterId).asSequence()
            .filter { it.status == "planned" && it.dueAt.isAfter(now) }
            .minOfOrNull { it.dueAt }

    private fun refreshAutonomousWake(context: Context) {
        ProactivePerceptionScheduler.scheduleNextDue(context.applicationContext)
    }

    private fun scheduleWake(context: Context, plan: RoleDayPlan, now: Instant,
        replace: Boolean = true) {
        val delay = Duration.between(now, plan.dueAt).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<RoleScheduleWakeWorker>()
            .setInputData(Data.Builder().putString("roleId", plan.characterId)
                .putString("planId", plan.id).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "role-own-plan-${plan.id}",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }

    /** Restore future self-appointed wakeups, but do not replay potentially
     * executed activities. Old due plans remain visible at the next decision. */
    fun restorePending(context: Context, now: Instant = Instant.now()) {
        all(context).filter { it.status == "planned" && it.dueAt.isAfter(now) }
            .forEach { plan ->
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
        commitmentTaskId: String = ""): RoleDayPlan {
        require(characterId.isNotBlank()) { "角色不能为空" }
        require(title.trim().length in 2..100 && intent.trim().length in 3..300) {
            "日程需要具体想做的事与本人的动机"
        }
        require(dueAt.isAfter(now.plusSeconds(60))) { "日程需要明确的未来时间" }
        val action = activityAction.trim().lowercase()
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
                it.id == linkedCommitment && it.dueAt == null &&
                    it.status == CommitmentTaskStatus.WaitingForFeedback
            }) { "只能关联本人已接受、没有定时器的持续约定" }
        }
        val linkedId = motiveId.trim()
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
        require(linkedCommitment.isBlank() || existing.none {
            it.characterId == characterId && it.commitmentTaskId == linkedCommitment &&
                it.status in setOf("planned", "due")
        }) { "这项约定已经有待执行的日程，请先兑现或改期" }
        // No arbitrary per-person plan quota: an ongoing life may have many distinct intentions.
        val plan = RoleDayPlan(UUID.randomUUID().toString(), characterId,
            title.trim(), intent.trim(), dueAt, now, activityAction = action,
            repeat = repeat, motiveId = linkedId, targetCharacterId = targetRole,
            targetResidentId = targetNative, locationCode = place,
            activityArgs = activityArgs.toString(),
            commitmentTaskId = linkedCommitment)
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
        now: Instant = Instant.now()): Boolean {
        val records = all(context)
        val target = records.firstOrNull { it.characterId == characterId && it.id == planId &&
            it.status in setOf("planned", "due") } ?: return false
        persist(context, records.map {
            if (it.id == target.id) it.copy(status = "cancelled", resolvedAt = now,
                resultSummary = reason.trim().take(180)) else it
        })
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("role-own-plan-$planId")
        refreshAutonomousWake(context)
        return true
    }

    @Synchronized
    fun reschedule(context: Context, characterId: String, planId: String,
        dueAt: Instant, reason: String, now: Instant = Instant.now()): RoleDayPlan {
        require(dueAt.isAfter(now.plusSeconds(60))) { "改期必须是未来时间" }
        val previous = all(context)
        val original = previous.firstOrNull { it.characterId == characterId && it.id == planId &&
            it.status in setOf("planned", "due") } ?: error("没有可改期的本人日程")
        val revised = original.copy(dueAt = dueAt, status = "planned",
            resultSummary = reason.trim().take(180), rescheduleCount = original.rescheduleCount + 1)
        persist(context, previous.map { if (it.id == planId) revised else it })
        try {
            scheduleWake(context, revised, now)
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

    /** Do not perform an action for a revoked wish, wrong target or old appointment. */
    fun canBegin(context: Context, characterId: String, planId: String, action: String,
        args: JSONObject, now: Instant = Instant.now()): Boolean {
        val plan = plans(context, characterId).firstOrNull { it.id == planId } ?: return false
        if (!canComplete(plan, characterId, action, "pending-action", now, args)) return false
        if (plan.commitmentTaskId.isNotBlank() && CommitmentTaskStore.active(characterId).none {
                it.id == plan.commitmentTaskId &&
                    it.status == CommitmentTaskStatus.WaitingForFeedback
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
                (it.commitmentTaskId.isBlank() || CommitmentTaskStore.active(characterId).any { task ->
                    task.id == it.commitmentTaskId && task.status == CommitmentTaskStatus.WaitingForFeedback
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
        } else WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("role-own-plan-$planId")
        refreshAutonomousWake(context)
        return true
    }

    @Synchronized
    fun markDue(context: Context, characterId: String, planId: String): RoleDayPlan? {
        val existing = all(context)
        val target = existing.firstOrNull {
            it.characterId == characterId && it.id == planId && it.status in setOf("planned", "due")
        } ?: return null
        if (target.status == "planned") {
            persist(context, existing.map { if (it.id == planId) it.copy(status = "due") else it })
        }
        return target.copy(status = "due")
    }

    /** A due plan is not a completed activity. Only wake the original role's decision loop. */
    fun dueContext(context: Context, characterId: String, now: Instant = Instant.now()): String =
        plans(context, characterId).filter { it.status == "due" ||
            (it.status == "planned" && !it.dueAt.isAfter(now)) }
            .take(3).joinToString("\n") {
                "待处理的本人日程 planId=${it.id}；原定活动：${it.title}；本人缘由：${it.intent}；时间：${it.dueAt}；" +
                    "关联意愿：${it.motiveId.ifBlank { "独立日程" }}；" +
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
                    "重新核实场景与用户意愿，可按原参数执行，也可适当调整；不自动调用。" +
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
        val current = RoleDayScheduleStore.plans(applicationContext, role).firstOrNull { it.id == planId }
            ?: return Result.success()
        if (current.status !in setOf("planned", "due")) return Result.success()
        if (Instant.now().isBefore(current.dueAt)) return Result.retry()
        val plan = RoleDayScheduleStore.markDue(applicationContext, role, planId)
            ?: return Result.success()
        return runCatching {
            // The app may be completely closed when this WorkManager job fires.
            // Initialize role stores and the online executor before dispatching.
            initializeBackgroundRuntime(applicationContext)
            ProactivePerceptionRuntime.initialize(applicationContext)
            ProactivePerceptionScheduler.scheduleSelfPlanned(applicationContext, role, plan.title)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
