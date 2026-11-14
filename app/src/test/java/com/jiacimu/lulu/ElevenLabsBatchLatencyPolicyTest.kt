package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class ElevenLabsBatchLatencyPolicyTest {
    @Test fun scribeAlreadyWaitedForVadSoDoNotWaitForAnotherBreathAfterNetworkAsr() {
        assertEquals(0L, CallVoiceConfiguration.postAsrContinuationMillis(
            "elevenlabs", muted = false, transcript = "刚才我说的事情还有好多好多"))
        assertEquals(0L, CallVoiceConfiguration.postAsrContinuationMillis(
            "elevenlabs", muted = true, transcript = "我说完了"))
        // Do not silently change other STT providers as part of this Scribe fix.
        assertEquals(3_200L, CallVoiceConfiguration.postAsrContinuationMillis(
            "groq", muted = false, transcript = "刚才我说的事情还有好多好多好多好多"))
        assertEquals(0L, CallVoiceConfiguration.postAsrContinuationMillis(
            "groq", muted = true, transcript = "我说完了"))
    }
}
