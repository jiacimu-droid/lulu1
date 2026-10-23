package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class SleepCallContinuationPolicyTest {
    @Test fun silenceAndMutedMicAreNotRequiredToWaitForAnotherUserReply() {
        assertTrue(SleepCallContinuationPolicy.canContinue(
            connected = true, sleepMode = true, thinking = false,
            speaking = false, opening = false, userSpeaking = false, realtime = false))
    }

    @Test fun pausesBecomeLessFrequentAsUnansweredSleepTimeGrows() {
        assertEquals(2_000L, SleepCallContinuationPolicy.nextPauseMillis(0L))
        assertEquals(2_000L, SleepCallContinuationPolicy.nextPauseMillis(6 * 60_000L - 1L))
        assertEquals(3_500L, SleepCallContinuationPolicy.nextPauseMillis(6 * 60_000L))
        assertEquals(3_500L, SleepCallContinuationPolicy.nextPauseMillis(12 * 60_000L - 1L))
        assertEquals(6_000L, SleepCallContinuationPolicy.nextPauseMillis(12 * 60_000L))
        assertEquals(6_000L, SleepCallContinuationPolicy.nextPauseMillis(20 * 60_000L - 1L))
        assertEquals(12_000L, SleepCallContinuationPolicy.nextPauseMillis(20 * 60_000L))
        assertEquals(12_000L, SleepCallContinuationPolicy.nextPauseMillis(27 * 60_000L - 1L))
        assertEquals(24_000L, SleepCallContinuationPolicy.nextPauseMillis(27 * 60_000L))
        assertEquals(24_000L, SleepCallContinuationPolicy.nextPauseMillis(30 * 60_000L))
    }

    @Test fun bedtimeDeadlineIsAbsoluteEvenWithPlaybackOrHallucinatedSpeech() {
        val deadline = 10_000L + SleepCallContinuationPolicy.AUTO_END_AT_MILLIS
        fun due(now: Long, connected: Boolean = true, deadlineAt: Long? = deadline) =
            SleepCallContinuationPolicy.shouldEndAtDeadline(deadlineAt, now, connected)
        assertFalse(due(10_000L))
        assertFalse(due(deadline - 1L))
        assertTrue(due(deadline))
        assertTrue(due(deadline + 8L * 60L * 60_000L))
        assertFalse(due(deadline, connected = false))
        assertFalse(due(deadline, deadlineAt = null))
        // Unlike the old policy, false STT, in-flight TTS, or sleepMode
        // disabled by a prior error cannot postpone an armed deadline.
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
