package com.jiacimu.lulu.ai

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicSignalSketchTest {
    private fun wav(format: Int = 1): ByteArray {
        // Four seconds of mono 8kHz 16-bit PCM: silence then steady sound.
        val pcm = ByteArray(32_000 * 2)
        for (frame in 16_000 until 32_000) {
            val value = 12000
            pcm[frame * 2] = (value and 255).toByte()
            pcm[frame * 2 + 1] = (value shr 8).toByte()
        }
        val b = ByteArray(44 + pcm.size)
        fun put16(at: Int, value: Int) {
            b[at] = value.toByte()
            b[at + 1] = (value shr 8).toByte()
        }
        fun put32(at: Int, value: Int) {
            put16(at, value and 65535)
            put16(at + 2, value ushr 16)
        }
        fun label(at: Int, text: String) {
            val v = text.toByteArray(Charsets.US_ASCII)
            v.copyInto(b, at)
        }
        label(0, "RIFF")
        put32(4, b.size - 8)
        label(8, "WAVE")
        label(12, "fmt ")
        put32(16, 16)
        put16(20, format)
        put16(22, 1)
        put32(24, 8000)
        put32(28, 16000)
        put16(32, 2)
        put16(34, 16)
        label(36, "data")
        put32(40, pcm.size)
        pcm.copyInto(b, 44)
        return b
    }

    @Test fun distinguishesRealQuietAndLoudWindows() {
        val detail = MusicSignalSketch.describe(wav())
        assertNotNull(detail)
        assertTrue(detail!!.contains("0秒:接近静默"))
        assertTrue(detail.contains("2秒:较强"))
        assertTrue(detail.contains("不是音符"))
    }

    @Test fun refusesUnsupportedFloatAndNonWaveData() {
        assertNull(MusicSignalSketch.describe(wav(format = 3)))
        assertNull(MusicSignalSketch.describe("not a wav".toByteArray()))
    }

    @Test fun rejectsTruncatedDataWithoutReadingBeyondBounds() {
        assertNull(MusicSignalSketch.describe(wav().copyOf(48)))
    }
}
