package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Automatic fact replacement verification must never buy a model call
 * every minute for the same failed/invalid answer. */
class MemorySupersessionRequestPolicyTest {
    private val hour = 60L * 60_000L
    private val t = 1_800_000_000_000L

    @Test fun aNewFactGetsOneOpportunityButFailedAttemptCannotRetryEachMinute() {
        assertTrue(memorySupersessionAttemptAllowed(0, 0L, t))
        assertFalse(memorySupersessionAttemptAllowed(1, t, t + 60_000L))
        assertFalse(memorySupersessionAttemptAllowed(1, t, t + hour - 1L))
        assertTrue(memorySupersessionAttemptAllowed(1, t, t + hour))
    }

    @Test fun dailyCeilingStopsAdditionalPaidReviewsEvenForDifferentFacts() {
        assertTrue(memorySupersessionAttemptAllowed(1, 0L, t))
        assertFalse(memorySupersessionAttemptAllowed(2, 0L, t))
        assertFalse(memorySupersessionAttemptAllowed(40, 0L, t))
    }

    @Test fun clockRewindDoesNotGrantAnEarlyRetry() {
        assertFalse(memorySupersessionAttemptAllowed(1, t, t - 1L))
    }
}
