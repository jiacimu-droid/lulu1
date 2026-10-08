package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoiceSynthesisPolicyTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun globalAutoReadOffBlocksCloudGenerationEvenIfTtsAndCharacterAreEnabled() {
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tts_enabled", true).putBoolean("tts_auto_speak", false).commit()
        CharacterVoicePreferenceStore.initialize(context)
        CharacterVoicePreferenceStore.setEnabled("auto-voice-test", true)
        assertTrue(VoiceSynthesisPolicy.enabled(context))
        assertFalse(VoiceSynthesisPolicy.automaticAllowed(context))
        assertFalse(VoiceSynthesisPolicy.chatAutomaticAllowed(context, "auto-voice-test"))
    }

    @Test fun perCharacterAutoReadOffBlocksOnlyAutomaticSpeech() {
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tts_enabled", true).putBoolean("tts_auto_speak", true).commit()
        CharacterVoicePreferenceStore.initialize(context)
        CharacterVoicePreferenceStore.setEnabled("auto-voice-test-b", false)
        assertTrue(VoiceSynthesisPolicy.automaticAllowed(context))
        assertFalse(VoiceSynthesisPolicy.chatAutomaticAllowed(context, "auto-voice-test-b"))
    }

    @Test fun disabledTtsBlocksAllAutomaticSpeech() {
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tts_enabled", false).putBoolean("tts_auto_speak", true).commit()
        assertFalse(VoiceSynthesisPolicy.enabled(context))
        assertFalse(VoiceSynthesisPolicy.automaticAllowed(context))
    }

    @Test fun usageLogStoresOnlyRequestMetadata() {
        VoiceUsageAudit.clear(context)
        VoiceUsageAudit.record(context, "ElevenLabs", "chat_auto", 28, "eleven_multilingual_v2")
        val lines = VoiceUsageAudit.recent(context)
        assertEquals(1, lines.size)
        assertTrue(lines.single().contains("chat_auto"))
        assertTrue(lines.single().contains("28字"))
        val raw = context.getSharedPreferences("lulu_cloud_voice_requests", Context.MODE_PRIVATE)
            .getString("attempts", "").orEmpty()
        assertFalse(raw.contains("xi-api-key"))
        assertFalse(raw.contains("speechText"))
    }
}
