package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class ConversationNaturalnessGateTest {
    @Test fun fatiguePlusWhatShouldIDoIsStronglyAssistantLike() {
        val result = ConversationNaturalnessGate.assess(
            "我学习累了",
            listOf("听起来你今天学习很累。你想让我做什么？"),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.reasons.any { it.contains("甩回") })
    }

    @Test fun naturalRoleOwnedResponsePasses() {
        val result = ConversationNaturalnessGate.assess(
            "我学习累了",
            listOf("先别硬撑了。", "我陪你晃会儿脑子。"),
        )
        assertFalse(result.needsRerender)
    }

    @Test fun outlineStyleCasualReplyGetsRerendered() {
        val result = ConversationNaturalnessGate.assess(
            "我今天有点烦",
            listOf("首先你可以休息一下，其次如果你愿意可以和我说说，最后我们再看看怎么办。"),
        )
        assertTrue(result.needsRerender)
        assertTrue(result.score >= 2)
    }

    @Test fun oneOrdinaryOfferDoesNotTriggerByItself() {
        val result = ConversationNaturalnessGate.assess(
            "今天还行",
            listOf("要不要出去转转？"),
        )
        assertFalse(result.needsRerender)
    }
}
