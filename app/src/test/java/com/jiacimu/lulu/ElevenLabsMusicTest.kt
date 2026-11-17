package com.jiacimu.lulu

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import com.jiacimu.lulu.data.CapabilityRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ElevenLabsMusicTest {
    @Test fun musicUsesExplicitCurrentModelAndNeverAcceptsSpeechVoiceIdAsFinetune() {
        val ordinary = ElevenLabsMusic.body("轻快的中文原创歌曲", 30, "music_v2_5", "")
        assertEquals("music_v2_5", ordinary.getString("model_id"))
        assertEquals(30000, ordinary.getInt("music_length_ms"))
        assertEquals(false, ordinary.getBoolean("force_instrumental"))
        assertFalse(ordinary.has("voice_id"))
        assertFalse(ordinary.has("finetune_id"))
        val custom = ElevenLabsMusic.body("原创人声小夜曲", 45, "music_v2", "music-finetune-role-a")
        assertEquals("music-finetune-role-a", custom.getString("finetune_id"))
        assertFalse(custom.has("voice_id"))
    }

    @Test fun musicRejectsInvalidInputsBeforeChargingAnApiCall() {
        assertThrows(IllegalArgumentException::class.java) {
            ElevenLabsMusic.body("", 30, "music_v2_5", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ElevenLabsMusic.body("a".repeat(4101), 30, "music_v2_5", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ElevenLabsMusic.body("原创", 601, "music_v2_5", "")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ElevenLabsMusic.body("原创", 30, "music_v1", "")
        }
    }

    @Test fun missingMusicScopeUsesActionableChineseErrorNotRawJson() {
        val json = """{"detail":{"type":"authentication_error","code":"unauthorized","message":"The API key you used is missing the permission music_generation to execute this operation.","status":"missing_permissions","request_id":"private-id"}}"""
        val text = ElevenLabsMusic.explainHttpError(401, json)
        assertTrue(text.contains("Music Generation"))
        assertTrue(text.contains("API Keys"))
        assertTrue(text.contains("编辑"))
        assertFalse(text.contains("private-id"))
        assertFalse(text.contains("authentication_error"))
        assertFalse(text.contains("请更换"))
        assertTrue(ElevenLabsMusic.explainHttpError(403, "{}").contains("权限"))
        assertTrue(ElevenLabsMusic.explainHttpError(402, "{}").contains("额度"))
        assertTrue(ElevenLabsMusic.explainHttpError(429, "{}").contains("频繁"))
    }

    @Test fun comparisonUsesExactOriginalAndMatchingRoleVoiceWithoutNewMusicRequests() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        val previousVoice = prefs.getString("eleven_voice_id", null)
        val roleId = "compare-" + UUID.randomUUID()
        val folderKey = MessageDigest.getInstance("SHA-256")
            .digest(roleId.toByteArray(Charsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }
        val folder = File(context.filesDir, "eleven_music/$folderKey")
        assertTrue(folder.mkdirs())
        try {
            prefs.edit().putString("eleven_voice_id", "voiceForSongCompare123").commit()
            val original = File(folder, "first.mp3").apply {
                writeBytes(ByteArray(2048) { 1 })
                setLastModified(1_000_000L)
            }
            val voiceVersion = ElevenLabsRoleSongPipeline.convertedFile(original, "voiceForSongCompare123")
                .apply { writeBytes(ByteArray(2048) { 2 }); setLastModified(1_010_000L) }
            assertEquals(original, ElevenLabsMusic.latestOriginalSong(context, roleId)?.file)
            assertEquals(voiceVersion, ElevenLabsMusic.latestRoleVoiceSong(context, roleId)?.file)
            assertFalse(ElevenLabsMusic.latestOriginalSong(context, roleId)!!.usedRoleVoice)
            assertTrue(ElevenLabsMusic.latestRoleVoiceSong(context, roleId)!!.usedRoleVoice)

            // Switching this role's voice must not play an older voice as the current one.
            prefs.edit().putString("eleven_voice_id", "anotherVoiceCompare123").commit()
            assertNull(ElevenLabsMusic.latestRoleVoiceSong(context, roleId))
            prefs.edit().putString("eleven_voice_id", "voiceForSongCompare123").commit()

            // A later song with failed voice conversion must not be paired with the first.
            val next = File(folder, "second.mp3").apply {
                writeBytes(ByteArray(2048) { 3 })
                setLastModified(2_000_000L)
            }
            assertEquals(next, ElevenLabsMusic.latestOriginalSong(context, roleId)?.file)
            assertNull(ElevenLabsMusic.latestRoleVoiceSong(context, roleId))
        } finally {
            folder.deleteRecursively()
            if (previousVoice == null) prefs.edit().remove("eleven_voice_id").commit()
            else prefs.edit().putString("eleven_voice_id", previousVoice).commit()
        }
    }

    @Test fun oneStoredElevenLabsKeyServesSpeechScribeAndMusicIndependently() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        prefs.edit().clear()
            .putString("eleven_api_key", "already-saved-key")
            .putString("eleven_voice_id", "regular-speech-voice")
            .putString("tts_provider", "elevenlabs")
            .putString("call_stt_mode", "elevenlabs")
            .putBoolean("tts_enabled", true)
            .putBoolean("eleven_music_enabled", true)
            .commit()
        com.jiacimu.lulu.data.CharacterVoicePreferenceStore.initialize(context)

        // This is the same preference key used by the older sleep/Scribe/TTS settings.
        assertNull(CallVoiceConfiguration.sttPreflight(context))
        assertNull(CallVoiceConfiguration.preflight(context, "lulu"))
        assertTrue(ElevenLabsMusic.isEnabledAndConfigured(context, "lulu"))

        // Choosing a different TTS provider must not remove Scribe/Music access.
        prefs.edit().putString("tts_provider", "system")
            .putString("eleven_music_model", "music_v2_5").commit()
        assertEquals("already-saved-key", prefs.getString("eleven_api_key", ""))
        assertNull(CallVoiceConfiguration.sttPreflight(context))
        assertTrue(ElevenLabsMusic.isEnabledAndConfigured(context, "lulu"))

        // Clearing the single owner credential invalidates both external consumers.
        prefs.edit().remove("eleven_api_key").commit()
        assertNotNull(CallVoiceConfiguration.sttPreflight(context))
        assertFalse(ElevenLabsMusic.isEnabledAndConfigured(context, "lulu"))
    }

    @Test fun roleSingingIsExplicitlyEnabledAndFinetuneIsRoleScoped() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val cap = requireNotNull(CapabilityRegistry.find("sing_song"))
        assertFalse(ElevenLabsMusic.isEnabledAndConfigured(context, "lulu"))
        assertFalse(CapabilityRegistry.allows(context, "lulu", cap, true))
        prefs.edit().putString("eleven_api_key", "local-test-key")
            .putBoolean("eleven_music_enabled", true)
            .putString("eleven_music_finetune:lulu", "vocal-a")
            .putString("eleven_music_finetune:role-b", "vocal-b").commit()
        assertTrue(CapabilityRegistry.allows(context, "lulu", cap, false))
        assertEquals("vocal-a", ElevenLabsMusic.finetuneId(context, "lulu"))
        assertEquals("vocal-b", ElevenLabsMusic.finetuneId(context, "role-b"))
        assertFalse(CapabilityRegistry.allows(context, "resident:npc", cap, true))
        prefs.edit().putBoolean("eleven_music_enabled", false).commit()
        assertFalse(CapabilityRegistry.allows(context, "lulu", cap, true))
    }
}
