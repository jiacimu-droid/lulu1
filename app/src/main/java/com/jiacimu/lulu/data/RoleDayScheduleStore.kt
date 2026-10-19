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
                    o.optString("status", "planned"))
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
                .put("status", p.status))
        }
        check(store(context).edit().putString(KEY, json.toString()).commit()) {
            "个人日程未能保存"
        }
        revisions.value += 1
    }

    @Synchronized
    fun arrange(context: Context, characterId: String, title: String, intent: String,
        dueAt: Instant, now: Instant = Instant.now()): RoleDayPlan {
        require(characterId.isNotBlank()) { "角色不能为空" }
        require(title.trim().length in 2..100 && intent.trim().length in 3..300) {
            "日程需要具体想做的事与本人的动机"
        }
        require(dueAt.isAfter(now.plusSeconds(60)) &&
            dueAt.isBefore(now.plus(Duration.ofDays(14)))) { "请选择未来两周内的时间" }
        val existing = all(context)
        require(existing.count { it.characterId == characterId && it.status == "planned" } < 12) {
            "当前日程过多，请先完成或取消此前的安排"
        }
        val plan = RoleDayPlan(UUID.randomUUID().toString(), characterId,
            title.trim(), intent.trim(), dueAt, now)
        persist(context, existing + plan)
        try {
            val wait = Duration.between(now, dueAt).toMillis().coerceAtLeast(1_000L)
            val request = OneTimeWorkRequestBuilder<RoleScheduleWakeWorker>()
                .setInputData(Data.Builder().putString("roleId", characterId)
                    .putString("planId", plan.id).build())
                .setInitialDelay(wait, TimeUnit.MILLISECONDS)
                .build()
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("role-own-plan-${plan.id}", ExistingWorkPolicy.REPLACE, request)
        } catch (failure: Exception) {
            persist(context, existing)
            throw failure
        }
        return plan
    }

    @Synchronized
    fun cancel(context: Context, characterId: String, planId: String) {
        val records = all(context)
        val target = records.firstOrNull { it.characterId == characterId && it.id == planId }
            ?: return
        persist(context, records.map {
            if (it.id == target.id) it.copy(status = "cancelled") else it
        })
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork("role-own-plan-$planId")
    }

    @Synchronized
    fun markDue(context: Context, characterId: String, planId: String): RoleDayPlan? {
        val existing = all(context)
        val target = existing.firstOrNull {
            it.characterId == characterId && it.id == planId && it.status == "planned"
        } ?: return null
        persist(context, existing.map { if (it.id == planId) it.copy(status = "due") else it })
        return target
    }

    /** A due plan is not a completed activity. Only wake the original role's decision loop. */
    fun dueContext(context: Context, characterId: String, now: Instant = Instant.now()): String =
        plans(context, characterId).filter { it.status == "due" ||
            (it.status == "planned" && !it.dueAt.isAfter(now)) }
            .take(3).joinToString("\n") {
                "你曾自行安排：${it.title}；本人的缘由：${it.intent}；原定时间：${it.dueAt}。" +
                    "现在可按真实情况决定执行、延后或取消；已安排不等于已经做了。"
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
        val plan = RoleDayScheduleStore.markDue(applicationContext, role, planId)
            ?: return Result.success()
        if (Instant.now().isBefore(plan.dueAt.minusSeconds(60))) return Result.retry()
        return runCatching {
            ProactivePerceptionScheduler.scheduleManual(applicationContext, role)
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
