package com.jiacimu.lulu.data

import android.content.Context

/**
 * Owner-controlled resource budget for native residents, shared across all NPCs.
 * This is not a personality preference or a substitute for resident decisions.
 * It only limits how many NEW future agenda rows a daily model may schedule;
 * independent incoming-message replies, due-time choices and ordinary duties
 * retain their own evidence and execution paths.
 */
internal object ResidentDailyPolicy {
    private const val PREFS = "lulu_resident_activity_policy_v1"
    private const val AGENDA_LIMIT = "daily_agenda_limit"
    const val MAX_AGENDA_ITEMS = 8
    const val DEFAULT_AGENDA_ITEMS = 5
    const val MAX_RESIDENTS = 12
    private const val RESIDENT_CAP = "resident_population_cap"
    private const val ALLOW_SPAWN = "resident_auto_generation"

    /** Existing residents are never culled by lowering this limit. */
    fun maxResidents(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(RESIDENT_CAP, MAX_RESIDENTS).coerceIn(0, MAX_RESIDENTS)

    fun setMaxResidents(context: Context, count: Int): Boolean {
        if (count !in 0..MAX_RESIDENTS) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(RESIDENT_CAP, count).commit()
    }

    fun autoGenerationEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ALLOW_SPAWN, false)

    fun setAutoGenerationEnabled(context: Context, enabled: Boolean): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ALLOW_SPAWN, enabled).commit()

    /** Same owner preference gates template, bootstrap and model-proposed NPCs. */
    fun mayAddResident(context: Context, activeCount: Int): Boolean =
        autoGenerationEnabled(context) && activeCount < maxResidents(context)

    fun maxAgendaItems(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(AGENDA_LIMIT, DEFAULT_AGENDA_ITEMS).coerceIn(0, MAX_AGENDA_ITEMS)

    /** Reject invalid input rather than silently rewriting the owner's chosen budget. */
    fun setMaxAgendaItems(context: Context, count: Int): Boolean {
        if (count !in 0..MAX_AGENDA_ITEMS) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(AGENDA_LIMIT, count).commit()
    }
    // Retain the old API for callers while storing ONE authoritative switch.
    fun allowNewResidents(context: Context): Boolean = autoGenerationEnabled(context)

    fun setAllowNewResidents(context: Context, allowed: Boolean): Boolean =
        setAutoGenerationEnabled(context, allowed)

    private const val LEGACY_MODEL_REQUEST_LIMIT = "daily_model_decision_limit"
    private const val ACTIVITY_ENABLED = "resident_activity_enabled"

    fun activityEnabled(context: Context, role: String = ""): Boolean {
        if (role.isNotBlank() && !role.startsWith("resident:")) return true
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(ACTIVITY_ENABLED, prefs.getInt(LEGACY_MODEL_REQUEST_LIMIT, 2) != 0)
        return enabled && !prefs.getBoolean("paused:$role", false)
    }

    @Synchronized fun setActivityEnabled(context: Context, enabled: Boolean, role: String = ""): Boolean {
        require(role.isBlank() || role.startsWith("resident:"))
        val editor = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (role.isBlank()) editor.putBoolean(ACTIVITY_ENABLED, enabled)
        else if (enabled) editor.remove("paused:$role") else editor.putBoolean("paused:$role", true)
        return editor.commit()
    }

    fun isIndividuallyPaused(context: Context, role: String): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean("paused:$role", false)

    private fun modelDay(at: java.time.Instant): String =
        at.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    fun modelDecisionsUsed(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Int {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getString("model-day:$role", "") == modelDay(at))
            p.getInt("model-count:$role", 0).coerceAtLeast(0)
        else 0
    }

    internal fun remainingAgendaSlots(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Int {
        val zone = java.time.ZoneId.systemDefault()
        val day = at.atZone(zone).toLocalDate()
        val created = RoleDayScheduleStore.plans(context, role).count {
            it.createdAt.atZone(zone).toLocalDate() == day
        }
        return (maxAgendaItems(context) - created).coerceAtLeast(0)
    }

    internal fun remainingTodayAgendaDecisions(
        context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now(),
    ): Int {
        if (!role.startsWith("resident:")) return 0
        val day = at.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return RoleDayScheduleStore.plans(context, role).count { plan ->
            plan.status in setOf("planned", "due") &&
                plan.dueAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate() == day
        }
    }

    /** Count real transport handoffs for diagnostics. Only an explicit pause can block them. */
    @Synchronized fun recordModelAttemptIfEnabled(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Boolean {
        if (!role.startsWith("resident:")) return true
        if (!activityEnabled(context, role)) return false
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val used = modelDecisionsUsed(context, role, at)
        return p.edit().putString("model-day:$role", modelDay(at))
            .putInt("model-count:$role", (used.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            .commit()
    }
}
