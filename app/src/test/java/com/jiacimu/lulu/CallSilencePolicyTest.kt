package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallSilencePolicyTest {
    private fun due(connected: Boolean = true, sleep: Boolean = false,
        busy: Boolean = false, userSpeaking: Boolean = false, silence: Long = 60_000L,
        audio: Long = 60_000L, reflection: Long = 120_000L, failures: Int = 0) =
        CallSilencePolicy.shouldReflect(connected, sleep, busy, userSpeaking,
            silence, audio, reflection, failures)

    @Test fun quietConnectedCallsKeepReflectingWithoutAnyNewUserUtterance() {
        assertTrue(due())
        assertTrue(due(silence = 30 * 60_000L, reflection = 5 * 60_000L))
        assertFalse(due(silence = 59_999L))
        assertFalse(due(reflection = 119_999L))
        assertFalse(due(silence = 30 * 60_000L, reflection = 120_000L))
    }

    @Test fun reflectionNeverRacesSpeechOpeningOrAnEndedCall() {
        assertFalse(due(connected = false))
        assertFalse(due(sleep = true))
        assertFalse(due(busy = true))
        assertFalse(due(userSpeaking = true))
        assertFalse(due(audio = 44_999L))
        assertTrue(due(audio = 45_000L))
    }

    @Test fun repeatedFailuresBackOffRatherThanCallingEveryTimerTick() {
        assertFalse(due(reflection = 5 * 60_000L, failures = 3))
        assertTrue(due(reflection = 10 * 60_000L, failures = 3))
    }

    @Test fun mutedStudyCompanionshipIsObservationRatherThanFakeUserSpeech() {
        val scene = CallSilencePolicy.context(15 * 60_000L, true)
        assertTrue(scene.contains("用户没有新增发言"))
        assertTrue(scene.contains("已静音"))
        assertTrue(scene.contains("学习"))
        assertTrue(scene.contains("可选择 silent"))
        assertTrue(scene.contains("允许挂断"))
    }
}
