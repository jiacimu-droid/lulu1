package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.time.Instant

data class PerceptionWakePlan(
    val characterId: String,
    val anchorAt: Instant,
    val dueAt: Instant,
    val intervalMinutes: Long,
    val policySignature: String,
)

/** Chosen once per real activity/attempt anchor; checking the clock cannot move a wake-up. */
object PerceptionWakePlanStore {
    private const val PREFS = "lulu_perception_wake_plans_v1"
    private var prefs: android.content.SharedPreferences? = null
    private val mutablePlans = MutableStateFlow<Map<String, PerceptionWakePlan>>(emptyMap())
    val plans = mutablePlans.asStateFlow()
    private val mutableSchedulingError = MutableStateFlow("")
    val schedulingError = mutableSchedulingError.asStateFlow()

    fun recordSchedulingError(error: String) { mutableSchedulingError.value = error }

    @Synchronized fun initialize(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        mutablePlans.value = decode(prefs?.getString("plans", null))
    }

    @Synchronized fun resolve(
        characterId: String, anchorAt: Instant, signature: String,
        calculate: () -> PerceptionWakePlan,
    ): PerceptionWakePlan {
        mutablePlans.value[characterId]?.takeIf {
            it.anchorAt == anchorAt && it.policySignature == signature
        }?.let { return it }
        val plan = calculate()
        mutablePlans.value = mutablePlans.value + (characterId to plan)
        persist()
        return plan
    }

    /** A missed wake-up that lands in quiet hours waits for their actual end, without rerolling. */
    @Synchronized fun deferUntil(characterId: String, dueAt: Instant): PerceptionWakePlan? {
        val previous = mutablePlans.value[characterId] ?: return null
        if (previous.dueAt >= dueAt) return previous
        val next = previous.copy(dueAt = dueAt)
        mutablePlans.value = mutablePlans.value + (characterId to next)
        persist()
        return next
    }

    @Synchronized fun invalidate(characterId: String) {
        mutablePlans.value = mutablePlans.value - characterId
        persist()
    }

    private fun persist() {
        val root = JSONObject()
        mutablePlans.value.forEach { (id, plan) -> root.put(id, JSONObject()
            .put("anchorAt", plan.anchorAt.toString()).put("dueAt", plan.dueAt.toString())
            .put("intervalMinutes", plan.intervalMinutes).put("policySignature", plan.policySignature)) }
        prefs?.edit()?.putString("plans", root.toString())?.apply()
    }

    private fun decode(raw: String?): Map<String, PerceptionWakePlan> = runCatching {
        val root = JSONObject(raw ?: "{}")
        buildMap {
            root.keys().forEach { id ->
                runCatching {
                    val value = root.getJSONObject(id)
                    PerceptionWakePlan(id, Instant.parse(value.getString("anchorAt")),
                        Instant.parse(value.getString("dueAt")), value.getLong("intervalMinutes"),
                        value.getString("policySignature"))
                }.getOrNull()?.takeIf { it.intervalMinutes > 0 && it.dueAt >= it.anchorAt }
                    ?.let { put(id, it) }
            }
        }
    }.getOrDefault(emptyMap())
}
