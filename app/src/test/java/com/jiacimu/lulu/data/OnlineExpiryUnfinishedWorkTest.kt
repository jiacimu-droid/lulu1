package com.jiacimu.lulu.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineExpiryUnfinishedWorkTest {
    private val now = Instant.parse("2026-11-12T04:46:00Z")

    private fun receipt(tool: String, status: String, secondsAgo: Long,
                        message: String = "后台未打开应用"): SharedTimelineEvent {
        val execution = JSONObject()
            .put("tool", tool).put("requestId", "step-${secondsAgo}")
            .put("status", status)
            .put("result", JSONObject().put("success", status == "succeeded")
                .put("status", status).put("error", message).toString())
        return SharedTimelineEvent(
            id = "tool-test-${secondsAgo}", characterId = "jiang-du",
            channel = "实际工具执行", speaker = "执行器",
            content = execution.toString(), occurredAt = now.minusSeconds(secondsAgo),
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult,
        )
    }

    @Test fun expirySeesTheLastRealFailedPhoneOperation() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "failed", 50, "小红书输入失败"),
        ), now)
        assertTrue(reminder.contains("background_phone"))
        assertTrue(reminder.contains("小红书输入失败"))
        assertTrue(reminder.contains("未完成"))
        assertTrue(reminder.contains("本人选择"))
    }

    @Test fun laterSuccessfulRetrySupersedesEarlierFailureOfSameTool() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "failed", 80),
            receipt("background_phone", "succeeded", 20),
        ), now)
        assertFalse(reminder.contains("后台未打开应用"))
        assertTrue(reminder.isBlank())
    }

    @Test fun unrelatedOrStaleEventsDoNotReviveOldFailures() {
        val badSource = receipt("background_phone", "failed", 50)
            .copy(source = "legacy")
        val tooOld = receipt("background_phone", "failed", 1800)
        val userEvent = receipt("background_phone", "failed", 30)
            .copy(evidenceKind = EventEvidenceKind.UserStatement)
        val unrelated = receipt("send_private_message", "failed", 10)
        assertTrue(OnlineExpiryUnfinishedWork.render(
            listOf(badSource, tooOld, userEvent, unrelated), now).isBlank())
    }

    @Test fun unverifiedObservationIsNotProvenSuccess() {
        val reminder = OnlineExpiryUnfinishedWork.render(listOf(
            receipt("background_phone", "unverified", 20, "目标窗口尚无法核实"),
        ), now)
        assertTrue(reminder.contains("目标窗口尚无法核实"))
        assertFalse(reminder.contains("任务已完成"))
    }
}
