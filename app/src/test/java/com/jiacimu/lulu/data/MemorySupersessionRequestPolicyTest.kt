package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Each unresolved fact may be revisited; a repeated failure is not a
 * license to buy the same comparison every minute or cap other facts. */
class MemorySupersessionRequestPolicyTest {
    private val hour = 60L * 60_000L
    private val t = 1_800_000_000_000L

    @Test fun aNewFactIsEligibleImmediatelyAndFailureWaitsUntilLater() {
        assertTrue(memorySupersessionAttemptAllowed(0L, t))
        assertFalse(memorySupersessionAttemptAllowed(t, t + 60_000L))
        assertFalse(memorySupersessionAttemptAllowed(t, t + hour - 1L))
        assertTrue(memorySupersessionAttemptAllowed(t, t + hour))
    }

    @Test fun differentFactsHaveIndependentCooldownsRatherThanADailyLimit() {
        assertFalse(memorySupersessionAttemptAllowed(t, t + 60_000L))
        assertTrue(memorySupersessionAttemptAllowed(0L, t + 60_000L))
    }

    @Test fun systemClockRewindIsNotAnEarlyRetry() {
        assertFalse(memorySupersessionAttemptAllowed(t, t - 1L))
    }
}
