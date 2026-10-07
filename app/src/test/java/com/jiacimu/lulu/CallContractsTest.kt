package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class CallContractsTest {
    @Test fun miniMaxSelectionCannotRouteIntoElevenAgent() {
        assertFalse(CallVoiceConfiguration.usesAgent("minimax", "agent"))
        assertFalse(CallVoiceConfiguration.usesAgent("elevenlabs", "direct"))
        assertFalse(CallVoiceConfiguration.usesAgent("elevenlabs", null))
        assertTrue(CallVoiceConfiguration.usesAgent("elevenlabs", "agent"))
    }
    @Test fun miniAsrKeepsRegionButDoesNotCarryTtsQueryOrPath() {
        assertEquals("https://api.minimaxi.com/v1/speech_to_text", CallVoiceConfiguration.miniAsrEndpoint("https://api.minimaxi.com/v1/t2a_v2?GroupId=123"))
        assertEquals("https://api.minimax.io/v1/speech_to_text", CallVoiceConfiguration.miniAsrEndpoint("https://api.minimax.io/v1/t2a_v2"))
    }
    @Test fun wavContainsActualPcmWithValidHeader() {
        val pcm = byteArrayOf(1, 2, 3, 4)
        val wav = pcmWav(pcm)
        assertEquals("RIFF", String(wav.copyOfRange(0, 4)))
        assertEquals("WAVE", String(wav.copyOfRange(8, 12)))
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(40, b.getInt(4)); assertEquals(16000, b.getInt(24))
        assertEquals(4, b.getInt(40)); assertArrayEquals(pcm, wav.copyOfRange(44, wav.size))
    }
    @Test fun repeatingSameErrorHasNewIdAndOldDismissCannotHideIt() {
        val task = ChatReplyTaskManager.TaskContext("notice-test")
        task.reportError("余额不足")
        val old = ChatReplyTaskManager.state("notice-test").errorId
        task.reportError("余额不足")
        assertTrue(ChatReplyTaskManager.state("notice-test").errorId > old)
        ChatReplyTaskManager.clearError("notice-test", old)
        assertEquals("余额不足", ChatReplyTaskManager.state("notice-test").lastError)
    }
    @Test fun noticesExplainFailureAndHideCredentials() {
        assertTrue(chatErrorNotice("HTTP 402 insufficient balance").startsWith("账户余额或额度不足"))
        assertTrue(chatErrorNotice("no available channel").startsWith("没有可用模型渠道"))
        assertTrue(chatErrorNotice("HTTP 401 invalid api key").startsWith("账号密钥或访问权限不可用"))
        assertFalse(chatErrorNotice("Bearer secret-token sk-123456789abcdef").contains("secret-token"))
        assertFalse(chatErrorNotice("Bearer secret-token sk-123456789abcdef").contains("sk-123456789abcdef"))
    }
}
