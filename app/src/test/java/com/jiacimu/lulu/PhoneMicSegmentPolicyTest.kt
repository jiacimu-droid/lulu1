package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneMicSegmentPolicyTest {
    @Test fun reachingUploadSizeDoesNotRequireSilenceOrEndTheConversation() {
        assertFalse(PhoneMicSegmentPolicy.uploadChunkFull(PhoneMicSegmentPolicy.MAX_UPLOAD_BYTES - 2))
        assertTrue(PhoneMicSegmentPolicy.uploadChunkFull(PhoneMicSegmentPolicy.MAX_UPLOAD_BYTES))
        assertFalse(PhoneMicSegmentPolicy.finishedBySilence(0, 120, 650))
        assertFalse(PhoneMicSegmentPolicy.finishedBySilence(17, 140, 650))
        assertTrue(PhoneMicSegmentPolicy.finishedBySilence(22, 140, 650))
    }

    @Test fun elevatedRoomNoiseCanStillEndAnUtterance() {
        assertTrue(PhoneMicSegmentPolicy.startThreshold(350f, 350.0) > 350)
        assertTrue(PhoneMicSegmentPolicy.quietThreshold(350f, 440.0, 1800.0) > 440)
        assertTrue(PhoneMicSegmentPolicy.stableBackgroundNoise(65.0, 700.0))
        assertFalse(PhoneMicSegmentPolicy.stableBackgroundNoise(250.0, 700.0))
        assertFalse(PhoneMicSegmentPolicy.stationaryNoiseEnded(22, 90))
        assertFalse(PhoneMicSegmentPolicy.stationaryNoiseEnded(23, 18))
        assertFalse(PhoneMicSegmentPolicy.stationaryNoiseEnded(23, 90))
        assertTrue(PhoneMicSegmentPolicy.stationaryNoiseEnded(36, 90))
        assertEquals(1, PhoneMicSegmentPolicy.onsetFrames(330.0, 190.0))
        assertEquals(2, PhoneMicSegmentPolicy.onsetFrames(198.0, 190.0))
    }

    @Test fun lowGainSpeechHasAnAdaptiveSecondaryGateAndMeterIsReal() {
        val soft = PhoneMicSegmentPolicy.softSpeechThreshold(195f, 30.0)
        assertTrue(soft < 195.0)
        assertTrue(PhoneMicSegmentPolicy.softSpeechOnset(115.0, 90.0, 30.0, 195f, 3))
        assertFalse(PhoneMicSegmentPolicy.softSpeechOnset(60.0, 57.0, 45.0, 195f, 3))
        assertFalse(PhoneMicSegmentPolicy.softSpeechOnset(115.0, 90.0, 30.0, 195f, 1))
        assertEquals(0f, PhoneMicSegmentPolicy.displayLevel(0.0))
        assertTrue(PhoneMicSegmentPolicy.displayLevel(650.0) > PhoneMicSegmentPolicy.displayLevel(120.0))
    }

    @Test fun aLongNaturalExplanationAllowsBreathingPauses() {
        assertEquals(1450, PhoneMicSegmentPolicy.silenceMs(10, 500))
        assertEquals(1900, PhoneMicSegmentPolicy.silenceMs(22, 500))
        assertEquals(2200, PhoneMicSegmentPolicy.silenceMs(80, 500))
        assertEquals(2800, PhoneMicSegmentPolicy.silenceMs(120, 2800))
        assertEquals(3500, PhoneMicSegmentPolicy.silenceMs(120, 3500))
    }
}
