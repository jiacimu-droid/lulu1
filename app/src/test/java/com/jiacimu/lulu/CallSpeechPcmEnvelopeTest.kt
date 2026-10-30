package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallSpeechPcmEnvelopeTest {
    private fun pcm(frames: Int, amplitude: Int): ByteArray = ByteArray(frames * 2).also {
        for (i in 0 until frames) {
            it[i * 2] = (amplitude and 255).toByte()
            it[i * 2 + 1] = ((amplitude ushr 8) and 255).toByte()
        }
    }

    @Test fun playbackPositionNotNetworkArrivalOwnsMouth() {
        val sampleRate = 24_000
        val envelope = CallSpeechPcmEnvelope(sampleRate)
        // Provider queues 120ms of sound before playback starts.
        envelope.append(pcm(960, 0), 0, 1920)
        envelope.append(pcm(960, 12_000), 0, 1920)
        envelope.append(pcm(960, 0), 0, 1920)
        assertEquals(0f, envelope.levelAt(10L)!!, .001f)
        assertTrue(envelope.levelAt(45L)!! > .5f)
        assertEquals(0f, envelope.levelAt(85L)!!, .001f)
        assertNull(envelope.levelAt(200L))
        assertNull(envelope.levelAt(-1L))
    }

    @Test fun fragmentedPcmMaintainsFrameAndBucketContinuity() {
        val envelope = CallSpeechPcmEnvelope(24_000)
        val fortyMs = pcm(960, 9000)
        envelope.append(fortyMs, 0, 511)
        assertNotNull(envelope.levelAt(0L))
        assertNull(envelope.levelAt(80L))
        envelope.append(fortyMs, 511, fortyMs.size - 511)
        assertTrue(envelope.levelAt(0L)!! > .5f)
        assertNull(envelope.levelAt(40L))
        envelope.append(pcm(960, 0), 0, 1920)
        assertEquals(0f, envelope.levelAt(60L)!!, .001f)
    }

    @Test fun invalidPcmInputDoesNotCorruptCurrentTimeline() {
        val envelope = CallSpeechPcmEnvelope(24_000)
        val first = pcm(960, 10_000)
        envelope.append(first, 0, first.size)
        envelope.append(first, -1, first.size)
        envelope.append(first, first.size - 1, 4)
        assertTrue(envelope.levelAt(0L)!! > .5f)
        assertNull(envelope.levelAt(40L))
    }
}
