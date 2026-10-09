package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class SleepCallContinuationPolicyTest {
    @Test fun silenceAndMutedMicAreNotRequiredToWaitForAnotherUserReply() {
        assertTrue(SleepCallContinuationPolicy.canContinue(
            connected = true, sleepMode = true, thinking = false,
            speaking = false, opening = false, userSpeaking = false, realtime = false))
    }

    @Test fun doNotAutogenerateOverActualSpeechOrAudioOrWhileDisconnected() {
        fun eligible(connected: Boolean = true, sleepMode: Boolean = true,
            thinking: Boolean = false, speaking: Boolean = false, opening: Boolean = false,
            userSpeaking: Boolean = false, realtime: Boolean = false) =
            SleepCallContinuationPolicy.canContinue(connected, sleepMode, thinking, speaking,
                opening, userSpeaking, realtime)
        assertFalse(eligible(connected = false))
        assertFalse(eligible(sleepMode = false))
        assertFalse(eligible(thinking = true))
        assertFalse(eligible(speaking = true))
        assertFalse(eligible(opening = true))
        assertFalse(eligible(userSpeaking = true))
        assertFalse(eligible(realtime = true))
    }
}
