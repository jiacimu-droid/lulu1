package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CommitmentCallRetryPolicyTest {
    @Test fun connectedCallWakeChecksEveryFiveMinutesButOnlyThreeDecisionRounds() {
        assertEquals(300L, CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(1))
        assertEquals(300L, CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(2))
        assertEquals(150L, CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(3))
        assertNull(CommitmentCallRetryPolicy.nextInCallWakeReviewSeconds(4))
        val messageOnlyWake = CommitmentTask(characterId = "role",
            goal = "二十分钟后叫醒我", deliveryAction = "send_private_message")
        assertTrue(CommitmentCallRetryPolicy.isFinalCheck(messageOnlyWake, 4, false))
    }

    @Test fun eachWakeStageSchedulesAnIndependentDecisionOrFinalReview() {
        assertEquals("retry", CommitmentCallRetryPolicy.followUpKindForWakeRound(1, true))
        assertEquals("retry", CommitmentCallRetryPolicy.followUpKindForWakeRound(2, true))
        assertEquals("verification", CommitmentCallRetryPolicy.followUpKindForWakeRound(3, true))
        assertEquals("", CommitmentCallRetryPolicy.followUpKindForWakeRound(1, false))
    }

    @Test fun doNotInterruptEarlierWakeVoiceWhileItIsStillPlaying() {
        assertTrue(CommitmentCallRetryPolicy.shouldDeferInCallWakeDecision(
            "retry", true, thinking = false, speaking = true, opening = false))
        assertTrue(CommitmentCallRetryPolicy.shouldDeferInCallWakeDecision(
            "retry", true, thinking = true, speaking = false, opening = false))
        assertFalse(CommitmentCallRetryPolicy.shouldDeferInCallWakeDecision(
            "retry", true, thinking = false, speaking = false, opening = false))
        assertFalse(CommitmentCallRetryPolicy.shouldDeferInCallWakeDecision(
            "verification", true, thinking = false, speaking = true, opening = false))
        assertFalse(CommitmentCallRetryPolicy.shouldDeferInCallWakeDecision(
            "retry", false, thinking = false, speaking = true, opening = false))
    }

    @Test fun laterWakeContactMustComeFromCharacterRatherThanAttemptCount() {
        assertEquals("start_call", CommitmentCallRetryPolicy.wakeContactAction(
            1, "start_call", null, true))
        assertEquals("send_private_message", CommitmentCallRetryPolicy.wakeContactAction(
            1, "send_private_message", null, true))
        assertEquals("", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", null, true))
        assertEquals("", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", WakeActionPlanner.Contact.WAIT, true))
        assertEquals("start_call", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", WakeActionPlanner.Contact.CALL, true))
        assertEquals("", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", WakeActionPlanner.Contact.CALL, false))
        assertEquals("send_private_message", CommitmentCallRetryPolicy.wakeContactAction(
            3, "start_call", WakeActionPlanner.Contact.MESSAGE, true))
        // Contradictory fields do not authorize the executor to dial or text:
        // cue=WAIT owns the decision even when contact is stale/malformed.
        assertEquals("", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", WakeActionPlanner.Contact.CALL, true,
            WakeActionPlanner.Cue.WAIT))
        assertEquals("", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", WakeActionPlanner.Contact.MESSAGE, true,
            WakeActionPlanner.Cue.WAIT))
        assertEquals("start_call", CommitmentCallRetryPolicy.wakeContactAction(
            2, "start_call", WakeActionPlanner.Contact.CALL, true,
            WakeActionPlanner.Cue.RING))
    }

    private val phoneWake = CommitmentTask(
        characterId = "role", goal = "明天8点叫醒我",
        deliveryAction = "start_call",
    )
    private val simpleCall = CommitmentTask(
        characterId = "role", goal = "明天8点联系",
        deliveryAction = "start_call",
    )

    @Test fun wakeCallRetriesAreFiniteWithFinalObservationOnly() {
        assertEquals(300L, CommitmentCallRetryPolicy.nextDelaySeconds(phoneWake, 1, true))
        assertEquals(480L, CommitmentCallRetryPolicy.nextDelaySeconds(phoneWake, 2, true))
        assertEquals(150L, CommitmentCallRetryPolicy.nextDelaySeconds(phoneWake, 3, true))
        assertNull(CommitmentCallRetryPolicy.nextDelaySeconds(phoneWake, 4, true))
        assertFalse(CommitmentCallRetryPolicy.isFinalCheck(phoneWake, 3, true))
        assertTrue(CommitmentCallRetryPolicy.isFinalCheck(phoneWake, 4, true))
    }

    @Test fun normalPromisedPhoneDoesNotRedial() {
        assertEquals(150L, CommitmentCallRetryPolicy.nextDelaySeconds(simpleCall, 1, true))
        assertTrue(CommitmentCallRetryPolicy.isFinalCheck(simpleCall, 2, true))
    }
}
