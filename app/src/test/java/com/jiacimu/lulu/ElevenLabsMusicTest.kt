package com.jiacimu.lulu

import android.content.Context
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
