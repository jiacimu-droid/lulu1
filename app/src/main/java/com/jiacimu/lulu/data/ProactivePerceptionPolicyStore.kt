package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import kotlin.math.roundToLong

enum class PerceptionIntervalUnit(val label: String) {
    Minutes("分钟"),
    Hours("小时"),
}

data class ProactivePerceptionPolicy(
    val enabled: Boolean = true,
    val adaptiveFrequency: Boolean = true,
    val quietHoursEnabled: Boolean = true,
    val rememberedAdaptiveFrequency: Boolean = true,
    val rememberedQuietHoursEnabled: Boolean = true,
    val quietStartMinutesOfDay: Int = 23 * 60,
    val quietEndMinutesOfDay: Int = 7 * 60,
    val intervalValue: Int = 2,
    /** Price-aware autonomous thinking cadence while online; incoming events are independent. */
    val onlinePulseSeconds: Int = 300,
    val intervalUnit: PerceptionIntervalUnit = PerceptionIntervalUnit.Hours,
) {
    val baseIntervalMinutes: Long
        get() = when (intervalUnit) {
            PerceptionIntervalUnit.Minutes -> intervalValue.toLong()
            PerceptionIntervalUnit.Hours -> intervalValue.toLong() * 60L
        }.coerceAtLeast(1L)

    fun intervalMinutes(adaptiveMultiplier: Double = 1.0): Long =
        (baseIntervalMinutes * adaptiveMultiplier.coerceIn(0.1, 10.0)).roundToLong().coerceAtLeast(1L)

    fun normalized(): ProactivePerceptionPolicy = copy(
        quietStartMinutesOfDay = quietStartMinutesOfDay.coerceIn(0, 24 * 60 - 1),
        quietEndMinutesOfDay = quietEndMinutesOfDay.coerceIn(0, 24 * 60 - 1),
        intervalValue = intervalValue.coerceIn(1, 999),
        onlinePulseSeconds = onlinePulseSeconds.coerceIn(150, 300),
        adaptiveFrequency = if (enabled) adaptiveFrequency else false,
        quietHoursEnabled = if (enabled) quietHoursEnabled else false,
    )
}

/** Per-character timing and quiet-hours policy for the low-frequency proactive perception runtime. */
object ProactivePerceptionPolicyStore {
    private const val PREFS_NAME = "lulu_proactive_perception_policies"
    private const val KEY_POLICIES = "policies_v1"

    private val mutablePolicies = MutableStateFlow<Map<String, ProactivePerceptionPolicy>>(emptyMap())
    val policies: StateFlow<Map<String, ProactivePerceptionPolicy>> = mutablePolicies.asStateFlow()
    private var prefs: android.content.SharedPreferences? = null
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        if (prefs != null) return
        appContext = context.applicationContext
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        mutablePolicies.value = decode(prefs?.getString(KEY_POLICIES, null))
    }

    fun get(characterId: String): ProactivePerceptionPolicy {
        mutablePolicies.value[characterId]?.let { return it }
        val legacy = MigratedDomainStores.characters.get(characterId).contactPolicy
        // Native citizens have a genuine independent mind, but twelve
        // residents must not silently create twelve two-hourly paid model
        // loops. Their deterministic jobs are free; editable companionship
        // settings still allow more frequent autonomous thoughts if desired.
        if (ResidentCharacterBridge.nativeResident(characterId) != null) {
            return ProactivePerceptionPolicy(
                enabled = legacy.enabled,
                adaptiveFrequency = false,
                rememberedAdaptiveFrequency = false,
                quietHoursEnabled = true,
                intervalValue = 24,
                intervalUnit = PerceptionIntervalUnit.Hours,
            )
        }
        return ProactivePerceptionPolicy(
            enabled = legacy.enabled,
            adaptiveFrequency = legacy.adaptiveFrequency,
            quietHoursEnabled = legacy.quietHoursEnabled,
            rememberedAdaptiveFrequency = legacy.adaptiveFrequency,
            rememberedQuietHoursEnabled = legacy.quietHoursEnabled,
            quietStartMinutesOfDay = legacy.quietStartHour * 60,
            quietEndMinutesOfDay = legacy.quietEndHour * 60,
        )
    }

    @Synchronized
    fun update(characterId: String, transform: (ProactivePerceptionPolicy) -> ProactivePerceptionPolicy) {
        val cleanId = characterId.trim().ifBlank { "lulu" }
        val next = transform(get(cleanId)).normalized()
        mutablePolicies.value = mutablePolicies.value + (cleanId to next)
        persist()
        appContext?.let { context ->
            CharacterMotiveReviewScheduler.schedule(context, cleanId,
                CharacterInnerLifeStore.snapshot(cleanId), force = true)
            ProactivePerceptionScheduler.scheduleNextDue(context)
            if (UserInteractionPresenceStore.state.value.appVisible == true) BackgroundCompanionControl.refresh(context)
        }
    }

    private fun persist() {
        val root = JSONObject()
        mutablePolicies.value.forEach { (characterId, policy) ->
            root.put(characterId, JSONObject().apply {
                put("enabled", policy.enabled)
                put("adaptiveFrequency", policy.adaptiveFrequency)
                put("quietHoursEnabled", policy.quietHoursEnabled)
                put("rememberedAdaptiveFrequency", policy.rememberedAdaptiveFrequency)
                put("rememberedQuietHoursEnabled", policy.rememberedQuietHoursEnabled)
                put("quietStartMinutesOfDay", policy.quietStartMinutesOfDay)
                put("quietEndMinutesOfDay", policy.quietEndMinutesOfDay)
                put("intervalValue", policy.intervalValue)
                put("onlinePulseSeconds", policy.onlinePulseSeconds)
                put("intervalUnit", policy.intervalUnit.name)
            })
        }
        prefs?.edit()?.putString(KEY_POLICIES, root.toString())?.apply()
    }

    private fun decode(raw: String?): Map<String, ProactivePerceptionPolicy> = runCatching {
        val root = JSONObject(raw ?: "{}")
        buildMap {
            root.keys().forEach { characterId ->
                val item = root.optJSONObject(characterId) ?: return@forEach
                val enabled = item.optBoolean("enabled", true)
                val adaptive = item.optBoolean("adaptiveFrequency", true)
                val quiet = item.optBoolean("quietHoursEnabled", true)
                put(
                    characterId,
                    ProactivePerceptionPolicy(
                        enabled = enabled,
                        adaptiveFrequency = adaptive,
                        quietHoursEnabled = quiet,
                        rememberedAdaptiveFrequency = item.optBoolean("rememberedAdaptiveFrequency", adaptive),
                        rememberedQuietHoursEnabled = item.optBoolean("rememberedQuietHoursEnabled", quiet),
                        quietStartMinutesOfDay = item.optInt("quietStartMinutesOfDay", 23 * 60),
                        quietEndMinutesOfDay = item.optInt("quietEndMinutesOfDay", 7 * 60),
                        intervalValue = item.optInt("intervalValue", 2),
                        onlinePulseSeconds = item.optInt("onlinePulseSeconds", 300),
                        intervalUnit = runCatching {
                            PerceptionIntervalUnit.valueOf(item.optString("intervalUnit", PerceptionIntervalUnit.Hours.name))
                        }.getOrDefault(PerceptionIntervalUnit.Hours),
                    ).normalized(),
                )
            }
        }
    }.getOrDefault(emptyMap())
}
