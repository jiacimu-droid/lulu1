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

    fun maxAgendaItems(context: Context): Int =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(AGENDA_LIMIT, DEFAULT_AGENDA_ITEMS).coerceIn(0, MAX_AGENDA_ITEMS)

    /** Reject invalid input rather than silently rewriting the owner's chosen budget. */
    fun setMaxAgendaItems(context: Context, count: Int): Boolean {
        if (count !in 0..MAX_AGENDA_ITEMS) return false
        return context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(AGENDA_LIMIT, count).commit()
    }
    // Separate from the daily agenda budget: deleting residents should not
    // silently repopulate the world on the next autonomous evolution tick.
    private const val ALLOW_NEW_RESIDENTS = "allow_new_residents"

    fun allowNewResidents(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ALLOW_NEW_RESIDENTS, true)

    fun setAllowNewResidents(context: Context, allowed: Boolean): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ALLOW_NEW_RESIDENTS, allowed).commit()

}
