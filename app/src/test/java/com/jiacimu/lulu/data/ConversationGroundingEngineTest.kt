package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ConversationGroundingEngineTest {
    @Test fun explicitConfirmationCanGroundLatestCandidate() {
        assertTrue(ConversationGroundingEngine.userConfirmsCandidate("对，就是这个意思"))
        assertTrue(ConversationGroundingEngine.userConfirmsCandidate("嗯对"))
        assertTrue(ConversationGroundingEngine.userConfirmsCandidate("你终于懂了！"))
    }

    @Test fun ordinaryTopicWordsDoNotAccidentallyConfirmCandidate() {
        assertFalse(ConversationGroundingEngine.userConfirmsCandidate("对了，我还有件事"))
        assertFalse(ConversationGroundingEngine.userConfirmsCandidate("是不是这个意思？"))
        assertFalse(ConversationGroundingEngine.userConfirmsCandidate("我不是这个意思"))
    }
}
