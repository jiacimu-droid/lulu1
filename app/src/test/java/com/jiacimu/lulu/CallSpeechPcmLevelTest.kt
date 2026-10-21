package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CallSpeechPcmLevelTest {
    @Test fun silenceIsClosedAndLivePcmOpensMouth() {
        assertEquals(0f, CallSpeechPcmLevel.measure(ByteArray(200), 0, 200), 0.0001f)
        val bytes = ByteArray(200) { index -> if (index % 2 == 0) 0 else 0x40 }
        assertTrue(CallSpeechPcmLevel.measure(bytes, 0, bytes.size) > 0.4f)
    }
    @Test fun invalidBoundsNeverReadPastInput() {
        assertEquals(0f, CallSpeechPcmLevel.measure(ByteArray(8), -1, 8), 0.0001f)
        assertEquals(0f, CallSpeechPcmLevel.measure(ByteArray(8), 0, 9), 0.0001f)
    }
}
