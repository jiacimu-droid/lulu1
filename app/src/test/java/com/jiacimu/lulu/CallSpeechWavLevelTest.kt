package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files

class CallSpeechWavLevelTest {
    private fun ByteArrayOutputStream.le16(value: Int) {
        write(value and 255)
        write((value ushr 8) and 255)
    }

    private fun ByteArrayOutputStream.le32(value: Int) {
        le16(value and 65535)
        le16(value ushr 16)
    }

    private fun wav(channels: Int = 1, format: Int = 1): File {
        val rate = 16000
        val samples = ByteArrayOutputStream()
        // Exactly one second, with 100ms silence followed by a real loud
        // region. Use different channels to prove PCM interleaving works.
        for (i in 0 until rate) {
            for (channel in 0 until channels) {
                samples.le16(if (i in 3200..9599) 12000 else 0)
            }
        }
        val pcm = samples.toByteArray()
        val bytes = ByteArrayOutputStream()
        bytes.write("RIFF".toByteArray())
        bytes.le32(36 + pcm.size)
        bytes.write("WAVEfmt ".toByteArray())
        bytes.le32(16)
        bytes.le16(format)
        bytes.le16(channels)
        bytes.le32(rate)
        bytes.le32(rate * channels * 2)
        bytes.le16(channels * 2)
        bytes.le16(16)
        bytes.write("data".toByteArray())
        bytes.le32(pcm.size)
        bytes.write(pcm)
        return Files.createTempFile("call-wav-mouth-", ".wav").toFile()
            .apply { writeBytes(bytes.toByteArray()) }
    }

    @Test fun realPlaybackTimeMovesMouthOnlyDuringAudiblePcm() {
        val file = wav()
        try {
            val reader = CallSpeechWavLevel.open(file)
            assertNotNull(reader)
            reader!!.use {
                assertEquals(0f, it.levelAt(100)!!, .0001f)
                assertTrue(it.levelAt(300)!! > .5f)
                assertEquals(0f, it.levelAt(700)!!, .0001f)
                assertEquals(0f, it.levelAt(1100)!!, .0001f)
            }
        } finally { file.delete() }
    }

    @Test fun stereoAndUnsupportedWavFailSafely() {
        val stereo = wav(channels = 2)
        val compressed = wav(format = 3)
        try {
            CallSpeechWavLevel.open(stereo)!!.use {
                assertTrue(it.levelAt(350)!! > .5f)
            }
            assertNull(CallSpeechWavLevel.open(compressed))
            assertNull(CallSpeechWavLevel.open(File("missing.wav")))
        } finally {
            stereo.delete()
            compressed.delete()
        }
    }
}
