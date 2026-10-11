package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallHangupGracePolicyTest {
    @Test fun companionWaitsAfterActualFarewellPlayback() {
        assertTrue(CallHangupGracePolicy.shouldWait(0L, false))
        assertTrue(CallHangupGracePolicy.shouldWait(3_000L, false))
        assertTrue(CallHangupGracePolicy.shouldWait(5_999L, false))
        assertFalse(CallHangupGracePolicy.shouldWait(6_000L, false))
    }

    @Test fun quietResponseCanFinishRecognitionBeforeCallCloses() {
        assertTrue(CallHangupGracePolicy.shouldWait(5_800L, true))
        assertTrue(CallHangupGracePolicy.shouldWait(6_000L, true))
        assertTrue(CallHangupGracePolicy.shouldWait(15_000L, true))
        assertFalse(CallHangupGracePolicy.shouldWait(18_000L, true))
        // Only a confirmed partial/final or typed reply permanently cancels
        // the pending disconnect; environmental VAD noise alone does not.
        assertFalse(CallHangupGracePolicy.shouldWait(12_000L, false))
    }
}
