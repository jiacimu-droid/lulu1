package com.jiacimu.lulu.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterVoicePreferenceStoreTest {
    @Test fun bedtimeVoiceIsPerCharacterAndNeverOverridesOrdinaryCall() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterVoicePreferenceStore.initialize(context)
        val advanced = context.getSharedPreferences("lulu_advanced_settings", 0)
        val oldProvider = advanced.getString("tts_provider", "system")
        val role = "test-sleep-voice-role"
        val another = "test-sleep-voice-other"
        try {
            advanced.edit().putString("tts_provider", "elevenlabs").commit()
            CharacterVoicePreferenceStore.setRealtimeVoiceId(role, "normal-bright-voice")
            CharacterVoicePreferenceStore.setSleepVoiceId(role, "gentle-low-voice")
            CharacterVoicePreferenceStore.setSleepVoiceEnabled(role, true)
            assertEquals("normal-bright-voice", CharacterVoicePreferenceStore.callVoiceId(role, false))
            assertEquals("gentle-low-voice", CharacterVoicePreferenceStore.callVoiceId(role, true))
            assertNotEquals("gentle-low-voice", CharacterVoicePreferenceStore.callVoiceId(another, true))
            CharacterVoicePreferenceStore.setSleepVoiceEnabled(role, false)
            assertEquals("normal-bright-voice", CharacterVoicePreferenceStore.callVoiceId(role, true))
            CharacterVoicePreferenceStore.setSleepVoiceEnabled(role, true)
            advanced.edit().putString("tts_provider", "system").commit()
            assertNotEquals("gentle-low-voice", CharacterVoicePreferenceStore.callVoiceId(role, true))
        } finally {
            advanced.edit().putString("tts_provider", oldProvider).commit()
            CharacterVoicePreferenceStore.setSleepVoiceEnabled(role, false)
            CharacterVoicePreferenceStore.setSleepVoiceId(role, "")
        }
    }
}
