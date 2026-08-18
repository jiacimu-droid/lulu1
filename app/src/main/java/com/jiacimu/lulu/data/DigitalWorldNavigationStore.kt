package com.jiacimu.lulu.data

import android.content.Context

/** Small durable hand-off used by clickable chat receipts to open a concrete digital home. */
object DigitalWorldNavigationStore {
    private const val PREFS_NAME = "lulu_digital_world_navigation"
    private const val KEY_HOME_CHARACTER_ID = "pending_home_character_id"

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
}
