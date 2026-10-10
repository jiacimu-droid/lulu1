package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PromptContextPolicyTest {
    @Test fun normalChatSkipsRawEvidenceButKeepsMemoryAndTimeline() {
        val b = PromptContextPolicy.forRequest("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "今天看见小猫啦", sceneContext = "私聊"))
        assertEquals(0, b.evidenceCharacters)
        assertTrue(b.memoryCharacters > 0)
        assertTrue(b.recentCharacters > 0)
        assertFalse(b.worldDetails)
        assertFalse(b.richInnerLife)
    }

    @Test fun promisesAndExactQuotesGainOriginalEvidence() {
        val b = PromptContextPolicy.forRequest("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "你上次说过要叫醒我，实际执行了吗？"))
        assertTrue(b.precise)
        assertTrue(b.evidenceCharacters >= 4_000)
        assertTrue(b.richInnerLife)
    }

    @Test fun anOldTopicDoesNotForceDetailedWorldOnEveryNewTurn() {
        val b = PromptContextPolicy.forRequest("聊天工具规划",
            UnifiedMemoryRequest(currentInput = "嗨", recentContext = "昨天我们聊了家具和约定"))
        assertFalse(b.precise)
        assertFalse(b.worldDetails)
    }

    @Test fun realWorldInteractionAndBackgroundLifeKeepDetails() {
        val meeting = PromptContextPolicy.forRequest("数字世界见面",
            UnifiedMemoryRequest(currentInput = "这个桌子能搬吗"))
        val background = PromptContextPolicy.forRequest("后台主动感知", UnifiedMemoryRequest())
        assertTrue(meeting.worldDetails)
        assertTrue(background.worldDetails)
        assertTrue(meeting.richInnerLife)
        assertTrue(background.evidenceCharacters > 0)
    }
}
