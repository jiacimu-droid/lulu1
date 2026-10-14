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

    @Test fun automaticCloudSpeechIsOptInForExistingInstallsWithoutSetting() {
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val original = prefs.getBoolean("tts_auto_speak", false)
        val wasSet = prefs.contains("tts_auto_speak")
        try {
            prefs.edit().putBoolean("tts_enabled", true).remove("tts_auto_speak").commit()
            assertFalse(VoiceSynthesisPolicy.automaticAllowed(context))
        } finally {
            val editor = prefs.edit()
            if (wasSet) editor.putBoolean("tts_auto_speak", original)
            else editor.remove("tts_auto_speak")
            editor.commit()
        }
    }

    @Test fun speechAuditSeparatesRealTimeSttFromTtsCharacters() {
        VoiceUsageAudit.clear(context)
        VoiceUsageAudit.record(context, "ElevenLabs", "chat_manual", 19, "eleven_v3")
        VoiceUsageAudit.record(context, "ElevenLabs", "stt_realtime_session",
            0, "scribe_v2_realtime", audioMs = 8_100, durationMs = 12_700)
        VoiceUsageAudit.record(context, "ElevenLabs", "stt_segment_sent",
            0, "scribe_v2", audioMs = 2_300)
        VoiceUsageAudit.record(context, "ElevenLabs", "agent_call_ended",
            0, "agent_realtime", durationMs = 5_000)
        val lines = VoiceUsageAudit.recent(context)
        assertTrue(lines.any { it.contains("chat_manual") && it.contains("19字") })
        assertTrue(lines.any { it.contains("stt_realtime_session") && it.contains("连接12秒") })
        val summary = VoiceUsageAudit.summary(context)
        assertTrue(summary.contains("TTS 1次"))
        assertTrue(summary.contains("实时 STT 1次"))
        assertTrue(summary.contains("Agent 电话1次"))
        VoiceUsageAudit.clear(context)
    }

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
