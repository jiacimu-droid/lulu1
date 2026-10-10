package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class PhoneMicSegmentPolicyTest {
    @Test fun reachingUploadSizeDoesNotRequireSilenceOrEndTheConversation() {
        assertFalse(PhoneMicSegmentPolicy.uploadChunkFull(PhoneMicSegmentPolicy.MAX_UPLOAD_BYTES - 2))
        assertTrue(PhoneMicSegmentPolicy.uploadChunkFull(PhoneMicSegmentPolicy.MAX_UPLOAD_BYTES))
        assertFalse(PhoneMicSegmentPolicy.finishedBySilence(0, 120, 650))
        assertFalse(PhoneMicSegmentPolicy.finishedBySilence(8, 140, 650))
        assertTrue(PhoneMicSegmentPolicy.finishedBySilence(17, 140, 650))
    }

    @Test fun elevatedRoomNoiseCanStillEndAnUtterance() {
        assertTrue(PhoneMicSegmentPolicy.startThreshold(350f, 300.0) > 350)
        assertTrue(PhoneMicSegmentPolicy.quietThreshold(350f, 440.0, 1800.0) > 440)
        assertTrue(PhoneMicSegmentPolicy.stableBackgroundNoise(65.0, 700.0))
        assertFalse(PhoneMicSegmentPolicy.stableBackgroundNoise(250.0, 700.0))
        assertFalse(PhoneMicSegmentPolicy.stationaryNoiseEnded(22, 90))
        assertFalse(PhoneMicSegmentPolicy.stationaryNoiseEnded(23, 18))
        assertTrue(PhoneMicSegmentPolicy.stationaryNoiseEnded(23, 90))
    }

    @Test fun aLongNaturalExplanationAllowsBreathingPauses() {
        assertEquals(850, PhoneMicSegmentPolicy.silenceMs(10, 500))
        assertEquals(1200, PhoneMicSegmentPolicy.silenceMs(22, 500))
        assertEquals(1700, PhoneMicSegmentPolicy.silenceMs(80, 500))
        assertEquals(2800, PhoneMicSegmentPolicy.silenceMs(120, 2800))
    }
}
