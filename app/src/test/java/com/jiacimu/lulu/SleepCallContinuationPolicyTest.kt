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
        assertEquals(12_000L, SleepCallContinuationPolicy.nextPauseMillis(40 * 60_000L - 1L))
        assertEquals(24_000L, SleepCallContinuationPolicy.nextPauseMillis(40 * 60_000L))
        assertEquals(24_000L, SleepCallContinuationPolicy.nextPauseMillis(60 * 60_000L))
    }

    @Test fun unattendedSleepCallEndsOnlyAfterDeadlineAndNoAudibleOrUserSpeech() {
        fun canEnd(silence: Long, connected: Boolean = true, enabled: Boolean = true,
            userSpeaking: Boolean = false, audioBusy: Boolean = false, autoEnd: Boolean = true) =
            SleepCallContinuationPolicy.shouldQuietlyEnd(silence, connected, enabled,
                userSpeaking, audioBusy, autoEnd)
        assertFalse(canEnd(62 * 60_000L, autoEnd = false))
        assertFalse(canEnd(20 * 60_000L))
        assertFalse(canEnd(40 * 60_000L))
        assertFalse(canEnd(60 * 60_000L - 1L))
        assertTrue(canEnd(60 * 60_000L))
        assertTrue(canEnd(62 * 60_000L))
        assertFalse(canEnd(60 * 60_000L, connected = false))
        assertFalse(canEnd(60 * 60_000L, enabled = false))
        assertFalse(canEnd(60 * 60_000L, userSpeaking = true))
        assertFalse(canEnd(60 * 60_000L, audioBusy = true))
        // Any newly recognized response resets the elapsed-silence input.
        assertFalse(canEnd(0L))
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
