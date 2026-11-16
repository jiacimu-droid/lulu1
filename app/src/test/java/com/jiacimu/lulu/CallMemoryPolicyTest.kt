package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import org.junit.Assert.*
import org.junit.Test

class CallMemoryPolicyTest {
    @Test fun callsDoNotBecomeHighStrengthMemoriesByDefault() {
        assertFalse(CallMemoryPolicy.DUPLICATE_FULL_CALL_AS_STRONG_MEMORY)
        assertTrue(CallMemoryPolicy.TRANSCRIPT_STILL_AVAILABLE)
        assertTrue(CallMemoryPolicy.IMPORTANT_DETAILS_STILL_EXTRACTABLE)
        assertTrue("voice-sleep-callId-agent-id".startsWith("voice-sleep-"))
        assertFalse("voice-callId-agent-id".startsWith("voice-sleep-"))
    }

    @Test fun ordinaryCallHistoryKeepsDialogueButNotLongBedtimeNarration() {
        val messages = listOf(
            LuluChatMessage(id = "voice-call-user-1", conversationId = "phone",
                sender = LuluChatMessage.Sender.User, content = "刚才想起上次的小事"),
            LuluChatMessage(id = "voice-sleep-call-agent-2", conversationId = "phone",
                sender = LuluChatMessage.Sender.Character, content = "九百字的睡前故事：虚构的城堡与龙"),
            LuluChatMessage(id = "voice-call-agent-3", conversationId = "phone",
                sender = LuluChatMessage.Sender.Character, content = "我们现在聊点别的吧"),
        )
        val history = buildCallHistory(messages, "电话角色")
        assertTrue(history.contains("刚才想起上次的小事"))
        assertTrue(history.contains("我们现在聊点别的吧"))
        assertFalse(history.contains("虚构的城堡与龙"))
    }
}
