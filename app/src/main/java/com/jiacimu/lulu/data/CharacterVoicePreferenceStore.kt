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
    private var advanced: android.content.SharedPreferences? = null
    @Volatile
    private var initializedApplication: Context? = null

    fun initialize(context: Context) {
        val application = context.applicationContext
        if (prefs != null && initializedApplication === application) return
        synchronized(this) {
            if (prefs != null && initializedApplication === application) return
            // Contexts are different after an Android/Robolectric application
            // restart. Never retain preferences from a previous application:
            // voice IDs and provider selection must be read from the same one.
            val loadedPrefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs = loadedPrefs
            advanced = application.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
            initializedApplication = application
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
        if (!enabled) com.jiacimu.lulu.ChatAutoVoicePlayback.onCharacterAutoReadChanged(cleanId, false)
    }

    fun realtimeVoiceId(characterId: String): String? = prefs?.getString("eleven_voice:$characterId", null)?.takeIf(String::isNotBlank)
    fun playbackVoiceId(characterId: String): String? = if (advanced?.getString("tts_provider", "system") == "elevenlabs")
        realtimeVoiceId(characterId) else voiceId(characterId)
    fun setRealtimeVoiceId(characterId: String, voiceId: String) {
        check(prefs?.edit()?.putString("eleven_voice:$characterId", voiceId.trim())?.putInt("eleven_voice_version:$characterId", (prefs?.getInt("eleven_voice_version:$characterId", 0) ?: 0) + 1)?.commit() == true)
    }

    private const val SLEEP_VOICE_PREFIX = "sleep_eleven_voice:"
    private const val SLEEP_VOICE_ENABLED_PREFIX = "sleep_eleven_enabled:"

    fun sleepVoiceId(characterId: String): String = prefs
        ?.getString(SLEEP_VOICE_PREFIX + characterId.trim(), "").orEmpty().trim()

    fun isSleepVoiceEnabled(characterId: String): Boolean = prefs
        ?.getBoolean(SLEEP_VOICE_ENABLED_PREFIX + characterId.trim(), false) == true

    fun setSleepVoiceId(characterId: String, voiceId: String) {
        val id = characterId.trim()
        if (id.isBlank()) return
        check(prefs?.edit()?.putString(SLEEP_VOICE_PREFIX + id, voiceId.trim())?.commit() == true)
    }

    fun setSleepVoiceEnabled(characterId: String, enabled: Boolean) {
        val id = characterId.trim()
        if (id.isBlank()) return
        check(prefs?.edit()?.putBoolean(SLEEP_VOICE_ENABLED_PREFIX + id, enabled)?.commit() == true)
    }

    /** The alternate voice applies only to opt-in bedtime calls on ElevenLabs. */
    fun callVoiceId(characterId: String, sleepMode: Boolean): String? {
        val regular = playbackVoiceId(characterId)
        if (advanced?.getString("tts_provider", "system") != "elevenlabs" || !sleepMode ||
            !isSleepVoiceEnabled(characterId)) return regular
        return sleepVoiceId(characterId).ifBlank { regular.orEmpty() }.ifBlank { null }
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
