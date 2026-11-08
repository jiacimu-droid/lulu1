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

    @Test fun oneDeliveredTurnCanUpdateExistingLifeWithoutSeparateImmediateRequest() {
        assertTrue(lifeTurnNeedsExtraction("好了，这件事取消了", "嗯，知道了", hasActiveLife = true))
        assertFalse(lifeTurnNeedsExtraction("好了，这件事取消了", "嗯，知道了", hasActiveLife = false))
        assertTrue(lifeTurnNeedsExtraction("我最近有新计划", "那就按照计划来", hasActiveLife = false))
        assertTrue(lifeTurnNeedsExtraction("先说一件事\n我今天起得早", "听到了", hasActiveLife = false))
        assertFalse(lifeTurnNeedsExtraction("嗨", "你好", hasActiveLife = true))
        // The role's own "我今天..." must not create a paid user-life
        // extraction just because the speaker used first-person language.
        assertFalse(lifeTurnNeedsExtraction("嗨", "我今天读了书", hasActiveLife = false))
    }

    @Test fun plainResolutionTurnCanBeCoalescedWithDeliveredChat() {
        assertTrue(looksLikeImmediateLifeUpdate("好了，我已经解决了"))
        assertTrue(looksLikeImmediateLifeUpdate("取消了原安排"))
        assertFalse(looksLikeImmediateLifeUpdate("今天吃什么"))
    }
}
