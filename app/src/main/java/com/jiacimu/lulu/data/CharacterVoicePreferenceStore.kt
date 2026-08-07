package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Per-character playback preference for generated chat replies. */
object CharacterVoicePreferenceStore {
    private const val PREFS_NAME = "lulu_character_voice_preferences"
    private const val KEY_PREFIX = "auto_play_"

    private val mutable = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val autoPlayReplies: StateFlow<Map<String, Boolean>> = mutable.asStateFlow()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val loadedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = loadedPrefs
            mutable.value = buildMap {
                loadedPrefs.all.forEach { (key, value) ->
                    if (key.startsWith(KEY_PREFIX) && value is Boolean) {
                        put(key.removePrefix(KEY_PREFIX), value)
                    }
                }
            }
        }
    }

    fun isEnabled(characterId: String): Boolean = mutable.value[characterId] == true

    fun setEnabled(characterId: String, enabled: Boolean) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        prefs?.edit()?.putBoolean(KEY_PREFIX + cleanId, enabled)?.apply()
        mutable.update { current -> current + (cleanId to enabled) }
    }
}
