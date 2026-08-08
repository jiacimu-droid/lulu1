package com.jiacimu.lulu.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Per-character speech preferences for generated chat replies and MiniMax playback. */
object CharacterVoicePreferenceStore {
    private const val PREFS_NAME = "lulu_character_voice_preferences"
    private const val AUTO_PLAY_PREFIX = "auto_play_"
    private const val VOICE_ID_PREFIX = "voice_id_"

    private val mutableAutoPlay = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val autoPlayReplies: StateFlow<Map<String, Boolean>> = mutableAutoPlay.asStateFlow()

    private val mutableVoiceIds = MutableStateFlow<Map<String, String>>(emptyMap())
    val voiceIds: StateFlow<Map<String, String>> = mutableVoiceIds.asStateFlow()

    @Volatile
    private var prefs: android.content.SharedPreferences? = null

    fun initialize(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val loadedPrefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = loadedPrefs
            mutableAutoPlay.value = buildMap {
                loadedPrefs.all.forEach { (key, value) ->
                    if (key.startsWith(AUTO_PLAY_PREFIX) && value is Boolean) {
                        put(key.removePrefix(AUTO_PLAY_PREFIX), value)
                    }
                }
            }
            mutableVoiceIds.value = buildMap {
                loadedPrefs.all.forEach { (key, value) ->
                    if (key.startsWith(VOICE_ID_PREFIX) && value is String) {
                        val characterId = key.removePrefix(VOICE_ID_PREFIX)
                        val voiceId = value.trim()
                        if (characterId.isNotBlank() && voiceId.isNotBlank()) put(characterId, voiceId)
                    }
                }
            }
        }
    }

    fun isEnabled(characterId: String): Boolean = mutableAutoPlay.value[characterId] == true

    fun setEnabled(characterId: String, enabled: Boolean) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        prefs?.edit()?.putBoolean(AUTO_PLAY_PREFIX + cleanId, enabled)?.apply()
        mutableAutoPlay.update { current -> current + (cleanId to enabled) }
    }

    fun voiceId(characterId: String): String? = mutableVoiceIds.value[characterId.trim()]
        ?.trim()
        ?.takeIf(String::isNotBlank)

    fun setVoiceId(characterId: String, voiceId: String) {
        val cleanId = characterId.trim()
        if (cleanId.isBlank()) return
        val cleanVoiceId = voiceId.trim()
        if (cleanVoiceId.isBlank()) {
            prefs?.edit()?.remove(VOICE_ID_PREFIX + cleanId)?.apply()
            mutableVoiceIds.update { current -> current - cleanId }
        } else {
            prefs?.edit()?.putString(VOICE_ID_PREFIX + cleanId, cleanVoiceId)?.apply()
            mutableVoiceIds.update { current -> current + (cleanId to cleanVoiceId) }
        }
    }
}
