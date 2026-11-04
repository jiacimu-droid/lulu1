package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatLexiconRetryPolicyTest {
    @Test fun oneTransientFailureRetriesPromptlyAndSubsequentFailuresBackOff() {
        assertEquals(0L, lexiconRetryDelayMillis(0))
        assertEquals(2_000L, lexiconRetryDelayMillis(1))
        assertEquals(30_000L, lexiconRetryDelayMillis(2))
        assertEquals(120_000L, lexiconRetryDelayMillis(3))
        assertEquals(600_000L, lexiconRetryDelayMillis(4))
    }

    @Test fun repeatedProviderOutageDoesNotCauseUnlimitedPaidCalls() {
        assertNull(lexiconRetryDelayMillis(5))
        assertNull(lexiconRetryDelayMillis(200))
    }
}
