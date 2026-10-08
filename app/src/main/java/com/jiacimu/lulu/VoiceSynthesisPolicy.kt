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

    /** The global switch must not silently charge for automatic audio when it is off. */
    fun chatAutomaticAllowed(context: Context, characterId: String): Boolean =
        automaticAllowed(context) &&
            com.jiacimu.lulu.data.CharacterVoicePreferenceStore.isEnabled(characterId)
}
