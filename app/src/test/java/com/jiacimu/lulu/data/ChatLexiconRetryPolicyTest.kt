package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatLexiconRetryPolicyTest {
    @Test fun oneTransientFailureRetriesPromptlyAndSubsequentFailuresBackOff() {
        assertEquals(0L, lexiconRetryDelayMillis(0))
        assertEquals(15L * 60_000L, lexiconRetryDelayMillis(1))
        assertEquals(60L * 60_000L, lexiconRetryDelayMillis(2))
        assertEquals(6L * 60 * 60_000L, lexiconRetryDelayMillis(3))
        assertEquals(24L * 60 * 60_000L, lexiconRetryDelayMillis(4))
    }

    @Test fun repeatedProviderOutageKeepsUnresolvedWorkButOnlyRetriesDaily() {
        assertEquals(24L * 60 * 60_000L, lexiconRetryDelayMillis(5))
        assertEquals(24L * 60 * 60_000L, lexiconRetryDelayMillis(200))
    }

    @Test fun plainResolutionTurnCanBeCoalescedWithDeliveredChat() {
        assertTrue(looksLikeImmediateLifeUpdate("好了，我已经解决了"))
        assertTrue(looksLikeImmediateLifeUpdate("取消了原安排"))
        assertFalse(looksLikeImmediateLifeUpdate("今天吃什么"))
    }
}
