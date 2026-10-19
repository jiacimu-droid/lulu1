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
                    o.optInt("rescheduleCount", 0))
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
                .put("rescheduleCount", p.rescheduleCount))
        }
        check(store(context).edit().putString(KEY, json.toString()).commit()) {
            "个人日程未能保存"
        }
        revisions.value += 1
    }

    /** Only real, independently verifiable actions can settle a plan. */
    val verifiableActions = setOf(
        "read_book", "play_solo_game", "digital_world_action", "write_journal", "publish_moment",
    )

    private fun scheduleWake(context: Context, plan: RoleDayPlan, now: Instant) {
        val delay = Duration.between(now, plan.dueAt).toMillis().coerceAtLeast(1_000L)
        val request = OneTimeWorkRequestBuilder<RoleScheduleWakeWorker>()
            .setInputData(Data.Builder().putString("roleId", plan.characterId)
                .putString("planId", plan.id).build())
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
            "role-own-plan-${plan.id}", ExistingWorkPolicy.REPLACE, request)
    }

    @Synchronized
    fun arrange(context: Context, characterId: String, title: String, intent: String,
        dueAt: Instant, now: Instant = Instant.now(), activityAction: String = ""): RoleDayPlan {
        require(characterId.isNotBlank()) { "角色不能为空" }
        require(title.trim().length in 2..100 && intent.trim().length in 3..300) {
            "日程需要具体想做的事与本人的动机"
        }
        require(dueAt.isAfter(now.plusSeconds(60)) &&
            dueAt.isBefore(now.plus(Duration.ofDays(14)))) { "请选择未来两周内的时间" }
        val action = activityAction.trim().lowercase()
        require(action.isBlank() || action in verifiableActions) { "这项日程尚无可验证的执行动作" }
        val existing = all(context)
        require(existing.count { it.characterId == characterId && it.status in setOf("planned", "due") } < 12) {
            "当前日程过多，请先完成或取消此前的安排"
        }
        val plan = RoleDayPlan(UUID.randomUUID().toString(), characterId,
            title.trim(), intent.trim(), dueAt, now, activityAction = action)
        persist(context, existing + plan)
        try {
            scheduleWake(context, plan, now)
        } catch (failure: Exception) {
            persist(context, existing)
            throw failure
        }
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
        return true
    }

    @Synchronized
    fun reschedule(context: Context, characterId: String, planId: String,
        dueAt: Instant, reason: String, now: Instant = Instant.now()): RoleDayPlan {
        require(reason.trim().length >= 3) { "改期需要本人真实的理由" }
        require(dueAt.isAfter(now.plusSeconds(60)) &&
            dueAt.isBefore(now.plus(Duration.ofDays(14)))) { "改期只能安排在未来两周内" }
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
        return revised
    }

    /** Status projection only; the execution evidence remains in the canonical raw timeline. */
    @Synchronized
    fun completeFromReceipt(context: Context, characterId: String, planId: String,
        action: String, evidenceId: String, summary: String, now: Instant = Instant.now()): Boolean {
        if (planId.isBlank() || evidenceId.isBlank() || action !in verifiableActions) return false
        val previous = all(context)
        val plan = previous.firstOrNull { it.id == planId && it.characterId == characterId &&
            it.status == "due" && (it.activityAction.isBlank() || it.activityAction == action) }
            ?: return false
        // Duplicate tool receipts and replies cannot repeatedly complete a plan.
        persist(context, previous.map { if (it.id == plan.id) it.copy(status = "completed",
            resolvedAt = now, resultEvidenceId = evidenceId, resultSummary = summary.take(180)) else it })
        WorkManager.getInstance(context.applicationContext).cancelUniqueWork("role-own-plan-$planId")
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
                    "对应动作：${it.activityAction.ifBlank { "按具体活动选择真实执行工具" }}。" +
                    "想做就执行真实工具并把 planId 放入同一次 args；只有成功回执才算完成。" +
                    "改期调用 reschedule_personal_activity，放弃调用 cancel_personal_activity；" +
                    "仅想一想、聊天、失败、没有证据都不能冒充完成。"
            }

    @Synchronized
    fun clearCharacter(context: Context, characterId: String) {
        val targets = all(context).filter { it.characterId == characterId }
        targets.forEach { WorkManager.getInstance(context).cancelUniqueWork("role-own-plan-${it.id}") }
        persist(context, all(context).filterNot { it.characterId == characterId })
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
        RoleDayScheduleStore.markDue(applicationContext, role, planId) ?: return Result.success()
        return runCatching {
            ProactivePerceptionScheduler.scheduleManual(applicationContext, role)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
