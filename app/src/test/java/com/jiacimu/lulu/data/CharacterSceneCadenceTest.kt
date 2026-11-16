package com.jiacimu.lulu.data

import com.jiacimu.lulu.CallSilencePolicy
import com.jiacimu.lulu.games.GameCoopPacing
import org.junit.Assert.*
import org.junit.Test

/**
 * These are four scheduling adapters for ONE actor, not four personalities.
 * A scheduled observation can resolve to action, thought, speech or silence.
 */
class CharacterSceneCadenceTest {
    @Test fun privateWindowDoesNotShareTheFasterMeetingOrGameClock() {
        assertEquals(300, CharacterSceneCadence.PRIVATE_ONLINE_PULSE_SECONDS)
        assertEquals(CharacterSceneCadence.PRIVATE_ONLINE_PULSE_SECONDS,
            ProactivePerceptionPolicy().onlinePulseSeconds)
        assertTrue(CharacterSceneCadence.MEETING_INITIAL_IDLE_MS <
            CharacterSceneCadence.MEETING_QUIET_DECISION_MS)
        assertTrue(CharacterSceneCadence.MEETING_QUIET_DECISION_MS <
            CharacterSceneCadence.PRIVATE_ONLINE_PULSE_SECONDS * 1000L)
    }

    @Test fun phoneSilenceMayReobserveButNeverForcesSpeechOrInterruptsARealTurn() {
        assertTrue(CallSilencePolicy.shouldReflect(
            true, false, false, false,
            CharacterSceneCadence.PHONE_MIN_USER_SILENCE_MS,
            CharacterSceneCadence.PHONE_MIN_AUDIO_GAP_MS,
            CharacterSceneCadence.PHONE_SILENCE_REVIEW_MS, 0))
        assertFalse(CallSilencePolicy.shouldReflect(
            true, false, false, true,
            CharacterSceneCadence.PHONE_MIN_USER_SILENCE_MS,
            CharacterSceneCadence.PHONE_MIN_AUDIO_GAP_MS,
            CharacterSceneCadence.PHONE_SILENCE_REVIEW_MS, 0))
        assertFalse(CallSilencePolicy.shouldReflect(
            true, false, true, false,
            CharacterSceneCadence.PHONE_MIN_USER_SILENCE_MS,
            CharacterSceneCadence.PHONE_MIN_AUDIO_GAP_MS,
            CharacterSceneCadence.PHONE_SILENCE_REVIEW_MS, 0))
        assertTrue(CallSilencePolicy.context(60_000L, true).contains("reply 或 silent"))
    }

    @Test fun aFastGamePulseOnlyMovesAnAlreadyChosenPlan() {
        assertEquals(CharacterSceneCadence.GAME_PLANNED_STEP_MS,
            GameCoopPacing.PLANNED_STEP_MS)
        assertTrue(GameCoopPacing.canTick(true, true, false, false, false, false))
        assertFalse(GameCoopPacing.canTick(false, true, false, false, false, false))
        assertFalse(GameCoopPacing.canTick(true, false, false, false, false, false))
        assertFalse("an unchanged planned tile may not buy a second model turn",
            GameCoopPacing.mayReplanAfterTick(9, 9, "companion",
                emptySet(), false))
    }
}
