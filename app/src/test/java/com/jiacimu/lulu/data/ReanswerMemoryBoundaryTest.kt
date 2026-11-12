package com.jiacimu.lulu.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReanswerMemoryBoundaryTest {
    private val excluded = setOf("discarded-message-1", "discarded-message-1:group:role-a")

    @Test fun oldResponseDerivedMemoriesAreNotRecalledIntoItsReplacement() {
        assertTrue(shouldExcludeReplyDerivedMemory(
            "timeline-events:user-message|discarded-message-1|another-event", excluded))
        assertTrue(shouldExcludeReplyDerivedMemory(
            "timeline-batch:discarded-message-1:group:role-a", excluded))
        assertFalse(shouldExcludeReplyDerivedMemory(
            "timeline-events:user-message|other-character-message", excluded))
        assertFalse(shouldExcludeReplyDerivedMemory(
            "timeline-events:discarded-message-1", emptySet()))
        assertFalse(shouldExcludeReplyDerivedMemory("manual-fact", excluded))
    }

    @Test fun excludedEventIdsAreControlDataNotPartOfSemanticQuery() {
        val request = UnifiedMemoryRequest(
            currentInput = "原本的用户提问",
            recentContext = "仅包含旧回复以前的聊天历史",
            excludedEventIds = excluded,
        )
        assertFalse(request.retrievalQuery().contains("discarded-message-1"))
        assertTrue(request.excludedEventIds.contains("discarded-message-1"))
    }
}
