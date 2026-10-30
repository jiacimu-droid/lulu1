package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class LuluVoiceCallReplyActivityTest {
    @Test fun incomingCallShowsActualReplyLifecycleAcrossVoiceAndVideo() {
        val incoming = LuluVoiceCallState(
            characterName = "角色甲", phase = CallPhase.Connected,
            opening = true, thinking = true,
        )
        assertEquals("角色甲正在回复…", incoming.replyActivity)
        assertEquals("回复已生成，正在准备语音…",
            incoming.copy(thinking = false, generatedTranscript = "已经生成").replyActivity)
        assertEquals("角色甲正在说话",
            incoming.copy(thinking = false, generatedTranscript = "已经生成",
                speaking = true).replyActivity)
        assertEquals("角色甲正在准备开场…",
            incoming.copy(thinking = false).replyActivity)
        assertNull(incoming.copy(phase = CallPhase.Ended).replyActivity)
    }

    @Test fun idleConnectedCallDoesNotPretendToBeTyping() {
        val idle = LuluVoiceCallState(characterName = "角色乙", phase = CallPhase.Connected)
        assertNull(idle.replyActivity)
        assertEquals("对方正在回复…", idle.copy(characterName = "", thinking = true).replyActivity)
    }
}
