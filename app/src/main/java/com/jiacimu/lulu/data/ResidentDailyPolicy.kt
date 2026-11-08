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

    private const val MODEL_REQUEST_LIMIT = "daily_model_decision_limit"
    const val MAX_MODEL_DECISIONS = 8
    const val DEFAULT_MODEL_DECISIONS = 2

    /** Different from the number of scheduled agenda rows: this bounds
     * actual resident model requests, including mail and format correction. */
    fun maxModelDecisions(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(MODEL_REQUEST_LIMIT, DEFAULT_MODEL_DECISIONS)
            .coerceIn(0, MAX_MODEL_DECISIONS)

    fun setMaxModelDecisions(context: Context, limit: Int): Boolean =
        limit in 0..MAX_MODEL_DECISIONS &&
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit().putInt(MODEL_REQUEST_LIMIT, limit).commit()

    private fun modelDay(at: java.time.Instant): String =
        at.atZone(java.time.ZoneId.systemDefault()).toLocalDate().toString()

    fun modelDecisionsUsed(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Int {
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return if (p.getString("model-day:$role", "") == modelDay(at))
            p.getInt("model-count:$role", 0).coerceAtLeast(0)
        else 0
    }

    /** Actual remaining paid calls, not the independent number of agenda rows.
     * Called before a resident plans or wakes; the gateway remains the atomic
     * spending authority when several resident events compete. */
    fun remainingModelRequests(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Int =
        if (!role.startsWith("resident:")) Int.MAX_VALUE
        else (maxModelDecisions(context) - modelDecisionsUsed(context, role, at))
            .coerceAtLeast(0)

    /** A daily plan itself costs one request. Each future resident agenda wake
     * costs another fresh decision; existing due plans also require a slot.
     * Local duty changes and immediate steps in the SAME decision cost no
     * additional model call. Never reserve imaginary credits or delete plans. */
    internal fun affordableNewAgendaItems(configured: Int, remainingCalls: Int,
        pendingDueDecisions: Int = 0): Int =
        minOf(configured.coerceIn(0, MAX_AGENDA_ITEMS),
            (remainingCalls - 1 - pendingDueDecisions.coerceAtLeast(0)).coerceAtLeast(0))

    /** Read-only preflight for optional resident maintenance tasks.
     * This is NOT a reservation: the gateway still owns the atomic claim,
     * so races cannot evade the owner's total paid-request limit. */
    fun hasModelRequestAllowance(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Boolean =
        remainingModelRequests(context, role, at) > 0

    /** Existing planned appointments own their next choice; optional
     * housekeeping and spontaneous social wakes must not evict them. */
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

    /** The first personal plan and accepted future appointments reserve
     * their minimum model turns. Unread social events are preserved and shown
     * to the resident at the next real daily or due-time decision. */
    fun hasOptionalResidentWakeAllowance(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Boolean {
        if (!role.startsWith("resident:")) return true
        val remaining = remainingModelRequests(context, role, at)
        val nativeId = role.removePrefix("resident:")
        val needsPlan = ResidentDailyDecision.planFor(context, nativeId, at) == null
        val reserved = (if (needsPlan) 1 else 0) +
            remainingTodayAgendaDecisions(context, role, at)
        return remaining > reserved
    }

    /** Memory extraction, semantic review and growth are paid but optional. */
    fun hasBackgroundMaintenanceAllowance(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Boolean =
        hasOptionalResidentWakeAllowance(context, role, at)

    /** Local midnight when this resident's next configured daily allowance starts.
     * This is scheduling arithmetic only, not a new decision or paid request. */
    fun nextModelBudgetDayStart(at: java.time.Instant = java.time.Instant.now()): java.time.Instant {
        val zone = java.time.ZoneId.systemDefault()
        return at.atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant()
    }

    /** Atomic attempt claim before networking. Constant-sized per-resident
     * counter; retry/correction must not sidestep the owner's cost limit. */
    @Synchronized fun claimModelDecision(context: Context, role: String,
        at: java.time.Instant = java.time.Instant.now()): Boolean {
        if (!role.startsWith("resident:")) return true
        val p = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val day = modelDay(at)
        val used = if (p.getString("model-day:$role", "") == day)
            p.getInt("model-count:$role", 0).coerceAtLeast(0)
        else 0
        if (used >= maxModelDecisions(context)) return false
        return p.edit().putString("model-day:$role", day)
            .putInt("model-count:$role", used + 1).commit()
    }

}
