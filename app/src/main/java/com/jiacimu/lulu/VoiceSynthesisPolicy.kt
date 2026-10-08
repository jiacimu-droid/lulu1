package com.jiacimu.lulu

import android.content.Context

/**
 * A shared gate for automatic speech. The app-wide Auto Read switch historically only
 * persisted a preference; callers must enforce it before *any* paid synthesis.
 *
 * An explicit user tap to replay an old message is not automatic speech.
 * Active phone calls have their own connect/hang-up lifecycle and are not auto-played chats.
 */
internal object VoiceSynthesisPolicy {
    private const val SETTINGS = "lulu_advanced_settings"

    fun enabled(context: Context): Boolean = context
        .getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        .getBoolean("tts_enabled", true)

    fun automaticAllowed(context: Context): Boolean {
        val p = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        return p.getBoolean("tts_enabled", true) && p.getBoolean("tts_auto_speak", true)
    }

    /** Audible automatic cloud speech only: sending TTS while media volume is zero wastes credits. */
    fun automaticPlayable(context: Context): Boolean {
        if (!automaticAllowed(context)) return false
        val prefs = context.getSharedPreferences(SETTINGS, Context.MODE_PRIVATE)
        val provider = prefs.getString("tts_provider", "system").orEmpty()
        if (provider !in setOf("elevenlabs", "minimax")) return true
        val manager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        return manager == null || manager.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 0
    }

    /** The global switch and individual role switch must both permit an audible automatic voice. */
    fun chatAutomaticAllowed(context: Context, characterId: String): Boolean =
        automaticPlayable(context) &&
            com.jiacimu.lulu.data.CharacterVoicePreferenceStore.isEnabled(characterId)
}
