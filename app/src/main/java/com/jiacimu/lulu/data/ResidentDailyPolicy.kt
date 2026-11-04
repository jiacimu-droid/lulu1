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
            .getBoolean(ALLOW_SPAWN, true)

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

}
