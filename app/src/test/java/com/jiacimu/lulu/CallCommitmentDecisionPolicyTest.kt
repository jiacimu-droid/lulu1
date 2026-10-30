package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class CallCommitmentDecisionPolicyTest {
    @Test fun actualQuietObservationCanAdvanceOriginalDutyWithoutNewUserSpeech() {
        assertTrue(CallCommitmentDecisionPolicy.canApply(false, true, listOf("review-observation")))
        assertFalse(CallCommitmentDecisionPolicy.canApply(false, true, emptyList()))
        assertFalse(CallCommitmentDecisionPolicy.canApply(true, true, listOf("review-observation")))
        assertTrue(CallCommitmentDecisionPolicy.canApply(false, false, listOf("user-utterance")))
    }
}
