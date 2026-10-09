package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class CommitmentCallRetryPolicyTest {
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
