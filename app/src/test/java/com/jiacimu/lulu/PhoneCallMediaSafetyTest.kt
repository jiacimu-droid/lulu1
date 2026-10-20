package com.jiacimu.lulu

import com.jiacimu.lulu.data.DeferredCallMediaAction
import com.jiacimu.lulu.data.ToolRouter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneCallMediaSafetyTest {
    @Test fun phoneDoesNotSpeakInternalSilenceReasoning() {
        assertTrue(PhoneSpokenContentBoundary.leaksInternalAnalysis(
            "电话里她麦克风静音了，等了约97秒没回应。她刚才说三分钟后放歌，" +
                "现在时间已经到了吗？我需要看时间线。上一轮角色发言时间2026-10-20T20:15:54Z。"))
        assertTrue(PhoneSpokenContentBoundary.leaksInternalAnalysis(
            """{"action":"reply","innerThought":"还得先确认时间"}"""))
        assertTrue(PhoneSpokenContentBoundary.leaksInternalAnalysis(
            "用户没有新增发言，我需要查看时间线记录。"))
    }

    @Test fun genuinelySpokenConversationIsNotUnreasonablySuppressed() {
        assertFalse(PhoneSpokenContentBoundary.leaksInternalAnalysis(
            "宝宝，那我陪你听会儿歌。你想说话的时候叫我。"))
        assertFalse(PhoneSpokenContentBoundary.leaksInternalAnalysis(
            "我看一眼时间，三分钟后我们就听歌。"))
        assertFalse(PhoneSpokenContentBoundary.leaksInternalAnalysis(
            "你刚才说的那句话我记住了，先让音乐陪你一会儿。"))
    }

    @Test fun musicMayBeScheduledInsideSamePhoneSession() {
        val inCall = JSONObject().put("trigger", "in_call").put("delaySeconds", 180)
            .put("command", "play").put("volumePercent", 35)
        assertNull(DeferredCallMediaAction.validate(inCall))
        assertNull(DeferredCallMediaAction.validate(JSONObject(inCall.toString())
            .put("trigger", "call_end")))
        assertNotNull(DeferredCallMediaAction.validate(JSONObject(inCall.toString())
            .put("delaySeconds", 90000)))
        assertNotNull(DeferredCallMediaAction.validate(JSONObject(inCall.toString())
            .put("volumePercent", 90)))
        assertNotNull(DeferredCallMediaAction.validate(JSONObject(inCall.toString())
            .put("command", "next")))
    }

    @Test fun failedToolResultPreservesNativeExplanation() {
        val tool = JSONObject().put("success", false).put("verification",
            "没有找到可控制的音乐播放器")
        assertEquals("没有找到可控制的音乐播放器",
            ToolRouter.failureDetail("control_music", tool))
        val unknown = JSONObject().put("success", false)
        assertTrue(ToolRouter.failureDetail("control_music", unknown)
            .contains("control_music"))
        assertEquals("waiting_user",
            ToolRouter.failureDetail("screen_action",
                JSONObject().put("status", "waiting_user")))
    }
}
