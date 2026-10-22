package com.jiacimu.lulu

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class VoiceAcousticAnalyzerTest {
    private fun tone(frequency: Double, seconds: Double = 1.5): ByteArray {
        val samples = (16000 * seconds).toInt()
        return ByteArray(samples * 2).also { pcm ->
            for (i in 0 until samples) {
                val s = (sin(2.0 * PI * frequency * i / 16000.0) * 9000).toInt()
                pcm[i * 2] = s.toByte()
                pcm[i * 2 + 1] = (s shr 8).toByte()
            }
        }
    }

    @Test fun unvoicedSilenceCannotInventMoodOrTurn() {
        val analyzer = VoiceAcousticAnalyzer()
        analyzer.consume(ByteArray(16000 * 2))
        assertNull(analyzer.finish())
        assertNull(analyzer.finish())
    }

    @Test fun pitchAndRecordingLevelCanBeObservedWithoutEmotionLabel() {
        val analyzer = VoiceAcousticAnalyzer()
        val pcm = tone(220.0)
        // STT uploads can split a user sentence; acoustic analysis must span chunks.
        analyzer.consume(pcm.copyOfRange(0, 12000))
        analyzer.consume(pcm.copyOfRange(12000, pcm.size))
        val hint = analyzer.finish()
        assertNotNull(hint)
        assertTrue(hint!!.contains("基频"))
        assertTrue(hint.contains("dBFS"))
        assertTrue(hint.contains("不是用户说的话"))
        assertFalse(hint.contains("情绪："))
        assertNull(analyzer.finish()) // no leaking into the next call turn
    }

    @Test fun veryShortSpeechHasNoFalseCertainty() {
        val analyzer = VoiceAcousticAnalyzer()
        analyzer.consume(tone(200.0, 0.2))
        assertNull(analyzer.finish())
    }
}
