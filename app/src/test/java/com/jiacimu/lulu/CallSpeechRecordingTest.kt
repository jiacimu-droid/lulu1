package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class CallSpeechRecordingTest {
    @Test fun playedPcmIsSavedWithPlayableMono24kWavHeader() {
        val folder = Files.createTempDirectory("call-wav-test").toFile()
        try {
            val target = File(folder, "phone.wav")
            val pcm = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
            val recording = CallSpeechRecording(target)
            recording.write(pcm, 0, 4)
            recording.write(pcm, 4, 4)
            assertTrue(recording.finish(delivered = true))
            val wav = target.readBytes()
            val data = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
            assertEquals("RIFF", String(wav, 0, 4, Charsets.US_ASCII))
            assertEquals("WAVE", String(wav, 8, 4, Charsets.US_ASCII))
            assertEquals("fmt ", String(wav, 12, 4, Charsets.US_ASCII))
            assertEquals(1, data.getShort(20).toInt()) // PCM format
            assertEquals(1, data.getShort(22).toInt()) // mono
            assertEquals(24_000, data.getInt(24))
            assertEquals(16, data.getShort(34).toInt()) // bits/sample
            assertEquals(8, data.getInt(40))
            assertArrayEquals(pcm, wav.copyOfRange(44, 52))
            assertFalse(File(folder, "phone.wav.partial").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun cancelledSpeechLeavesNoReplayablePartialFile() {
        val folder = Files.createTempDirectory("call-wav-cancel").toFile()
        try {
            val target = File(folder, "interrupted.wav")
            val recording = CallSpeechRecording(target)
            recording.write(byteArrayOf(0, 1, 2, 3), 0, 4)
            assertFalse(recording.finish(delivered = false))
            assertFalse(target.exists())
            assertFalse(File(folder, "interrupted.wav.partial").exists())
        } finally {
            folder.deleteRecursively()
        }
    }

    @Test fun repeatedMessageIdDoesNotReplaceFirstOriginalVoice() {
        val folder = Files.createTempDirectory("call-wav-first").toFile()
        try {
            val target = File(folder, "first.wav")
            val first = CallSpeechRecording(target)
            first.write(byteArrayOf(1, 2), 0, 2)
            assertTrue(first.finish(true))
            val original = target.readBytes()

            val retry = CallSpeechRecording(target)
            retry.write(byteArrayOf(3, 4), 0, 2)
            assertTrue(retry.finish(true))
            assertArrayEquals(original, target.readBytes())
        } finally {
            folder.deleteRecursively()
        }
    }
}
