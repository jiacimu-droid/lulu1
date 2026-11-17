package com.jiacimu.lulu

import android.content.Context
import com.jiacimu.lulu.data.CharacterVoicePreferenceStore
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ElevenLabsRoleSongPipelineTest {
    @Test fun twoStemClassifierNeverMistakesAccompanimentForVocals() {
        assertEquals("vocals", ElevenLabsRoleSongPipeline.stemKind("mix/vocals.mp3"))
        assertEquals("instrumental", ElevenLabsRoleSongPipeline.stemKind("mix/instrumental.mp3"))
        assertEquals("instrumental", ElevenLabsRoleSongPipeline.stemKind("mix/no_vocals.mp3"))
        assertEquals("instrumental", ElevenLabsRoleSongPipeline.stemKind("accompaniment.mp3"))
        assertNull(ElevenLabsRoleSongPipeline.stemKind("../../untrusted.txt"))
        assertNull(ElevenLabsRoleSongPipeline.stemKind("unknown.mp3"))
    }

    @Test fun voiceIdReusesExistingRolePreferenceAndDoesNotBorrowMinimaxVoice() {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("tts_provider", "minimax")
            .putString("minimax_voice_id", "minimax-other-voice")
            .putString("eleven_voice_id", "").commit()
        CharacterVoicePreferenceStore.initialize(context)
        CharacterVoicePreferenceStore.setRealtimeVoiceId("role-song-test", "roleEleven12345")
        assertEquals("roleEleven12345", ElevenLabsRoleSongPipeline.roleVoiceId(context, "role-song-test"))
        assertNull(ElevenLabsRoleSongPipeline.roleVoiceId(context, "role-without-eleven"))
        prefs.edit().putString("eleven_voice_id", "globalEleven12345").commit()
        assertEquals("globalEleven12345", ElevenLabsRoleSongPipeline.roleVoiceId(context, "role-without-eleven"))
        assertFalse(ElevenLabsRoleSongPipeline.enabled(context))
        prefs.edit().putBoolean("eleven_music_use_role_voice", true).commit()
        assertTrue(ElevenLabsRoleSongPipeline.enabled(context))
    }

    @Test fun conversionCacheSeparatesDifferentVoicesWithoutPuttingIdInFilename() {
        val source = File("/tmp/song.mp3")
        val a = ElevenLabsRoleSongPipeline.convertedFile(source, "firstVoice1234")
        val b = ElevenLabsRoleSongPipeline.convertedFile(source, "secondVoice1234")
        assertNotEquals(a.name, b.name)
        assertFalse(a.name.contains("firstVoice"))
        assertTrue(a.name.endsWith(".wav"))
        assertThrows(IllegalArgumentException::class.java) {
            ElevenLabsRoleSongPipeline.convertedFile(source, "../badVoice")
        }
    }

    @Test fun localMixerWritesStereoWavWithClippedSamples() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "singing_pcm_test")
        dir.mkdirs()
        val backing = File(dir, "backing.pcm")
        val voice = File(dir, "voice.pcm")
        val wav = File(dir, "mixed.wav")
        try {
            // Two stereo frames. First intentionally clips rather than wrapping.
            backing.writeBytes(pcm(30000, 30000, -10000, 10000))
            voice.writeBytes(pcm(30000, 30000, 10000, -10000))
            ElevenLabsSongAudioMix.writeMixedWav(backing, voice, wav)
            val bytes = wav.readBytes()
            assertEquals("RIFF", String(bytes.copyOfRange(0, 4)))
            assertEquals("WAVE", String(bytes.copyOfRange(8, 12)))
            assertEquals("data", String(bytes.copyOfRange(36, 40)))
            assertEquals(44 + 8, bytes.size)
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals(44100, buffer.getInt(24))
            assertEquals(2, buffer.getShort(22).toInt())
            assertEquals(32767, buffer.getShort(44).toInt())
            assertEquals(32767, buffer.getShort(46).toInt())
            assertEquals(ElevenLabsSongAudioMix.blend(-10000, 10000),
                buffer.getShort(48))
        } finally {
            backing.delete()
            voice.delete()
            wav.delete()
            dir.delete()
        }
    }

    private fun pcm(vararg samples: Int): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            .apply { samples.forEach { putShort(it.toShort()) } }.array()
}
