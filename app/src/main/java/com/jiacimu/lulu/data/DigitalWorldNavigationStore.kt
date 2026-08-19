package com.jiacimu.lulu.data

import android.content.Context

/** Small durable hand-off used by clickable chat receipts to open concrete digital-world content. */
object DigitalWorldNavigationStore {
    private const val PREFS_NAME = "lulu_digital_world_navigation"
    private const val KEY_HOME_CHARACTER_ID = "pending_home_character_id"
    private const val KEY_MEETING_SESSION_ID = "pending_meeting_session_id"

    fun requestHome(context: Context, characterId: String) {
        val clean = characterId.trim()
        if (clean.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_HOME_CHARACTER_ID, clean)
            .apply()
    }

    fun consumeHome(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_HOME_CHARACTER_ID, null)?.trim()?.takeIf(String::isNotBlank)
        if (value != null) prefs.edit().remove(KEY_HOME_CHARACTER_ID).apply()
        return value
    }

    fun requestMeeting(context: Context, sessionId: String) {
        val clean = sessionId.trim()
        if (clean.isBlank()) return
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_MEETING_SESSION_ID, clean)
            .apply()
    }

    fun consumeMeeting(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val value = prefs.getString(KEY_MEETING_SESSION_ID, null)?.trim()?.takeIf(String::isNotBlank)
        if (value != null) prefs.edit().remove(KEY_MEETING_SESSION_ID).apply()
        return value
    }
}
