package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/** Durable model/action boundary: interrupted thinking can retry; delivered actions are not replayed. */
internal object AutonomousPerceptionAttempts {
    private const val PREFS = "lulu_autonomous_attempts_v1"
    private const val RUNTIME_PREFS = "lulu_proactive_runtime_v2"
    private val session = UUID.randomUUID().toString()
    private val active = mutableSetOf<String>()

    data class Attempt(val characterId: String, val id: String)

    @Synchronized fun begin(context: Context, characterId: String, trigger: String, at: Instant): Attempt {
        val attempt = Attempt(characterId, UUID.randomUUID().toString())
        val record = JSONObject().put("id", attempt.id).put("owner", session)
            .put("characterId", characterId).put("trigger", trigger)
            .put("startedAt", at.toEpochMilli()).put("stage", "model")
        check(prefs(context).edit().putString(characterId, record.toString()).commit()) {
            "感知执行记录未保存"
        }
        active.add(attempt.id)
        return attempt
    }

    @Synchronized fun beforeAction(context: Context, characterId: String) {
        val record = read(context, characterId) ?: return
        if (record.optString("owner") != session || record.optString("id") !in active) return
        check(prefs(context).edit().putString(characterId,
            record.put("stage", "action").toString()).commit()) { "动作执行边界未保存" }
    }

    @Synchronized fun finish(context: Context, attempt: Attempt?, failed: Boolean) {
        if (attempt == null) return
        active.remove(attempt.id)
        val record = read(context, attempt.characterId) ?: return
        if (record.optString("id") != attempt.id) return
        if (failed) recover(context, record) else clear(context, attempt.characterId)
    }

    @Synchronized fun recoverInterrupted(
        context: Context,
        enqueue: (String, Long) -> Unit = { role, at ->
            ProactivePerceptionScheduler.scheduleBackgroundFailureRecovery(context, role, at)
        },
    ) {
        prefs(context).all.keys.toList().forEach { role ->
            val record = read(context, role) ?: return@forEach
            if (record.optString("owner") == session && record.optString("id") in active) return@forEach
            recover(context, record, enqueue)
        }
    }

    private fun recover(
        context: Context,
        record: JSONObject,
        enqueue: (String, Long) -> Unit = { role, at ->
            ProactivePerceptionScheduler.scheduleBackgroundFailureRecovery(context, role, at)
        },
    ) {
        val role = record.optString("characterId")
        if (role.isBlank()) return
        if (record.optString("stage") != "model") {
            CompanionPresenceStore.recordPerceptionAttempt(role, "上次在动作执行期间中断；保留已保存结果，不自动重放")
            clear(context, role)
            return
        }
        val runtime = context.getSharedPreferences(RUNTIME_PREFS, Context.MODE_PRIVATE)
        val retryKey = "background_failure_retry_at_$role"
        val now = System.currentTimeMillis()
        if (!ProactivePerceptionRecoveryPolicy.shouldRetry(
                runtime.getLong(retryKey, 0L), now, record.optString("trigger"),
                isChatBatch = false, enabled = ProactivePerceptionPolicyStore.get(role).enabled)) {
            clear(context, role)
            return
        }
        // Commit the cooldown before enqueue; remove it if durable work registration fails.
        if (!runtime.edit().putLong(retryKey, now).commit()) return
        runCatching { enqueue(role, record.optLong("startedAt", now)) }.onSuccess {
            clear(context, role)
        }.onFailure {
            runtime.edit().remove(retryKey).commit()
            CompanionPresenceStore.recordPerceptionAttempt(role,
                "感知恢复排程失败 · ${it.message.orEmpty().take(95)}")
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun read(context: Context, role: String): JSONObject? =
        prefs(context).getString(role, null)?.let { runCatching { JSONObject(it) }.getOrNull() }
    private fun clear(context: Context, role: String) { prefs(context).edit().remove(role).commit() }
}
