package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CharacterActionFeedbackTest {
    private val now = Instant.parse("2026-10-11T16:00:00Z")

    @Test fun actualSuccessAndFailureBothInformTheNextTurn() {
        val receipts = JSONArray()
            .put(JSONObject().put("at", now.minusSeconds(100).toString())
                .put("selected", "message").put("succeeded", true)
                .put("reason", "想了解通话为什么断了")
                .put("outcome", "私聊发送成功")
                .put("causalEvidenceId", "interaction-call-end-123"))
            .put(JSONObject().put("at", now.minusSeconds(30).toString())
                .put("selected", "call").put("succeeded", false)
                .put("reason", "之前没有联系上")
                .put("outcome", "对方未接听"))
        val memory = CharacterActionFeedback.recent(receipts, now)
        assertTrue(memory.contains("私聊发送成功"))
        assertTrue(memory.contains("执行失败或未完成"))
        assertTrue(memory.contains("interaction-call-end-123"))
        assertTrue(memory.contains("不能反复假称已执行"))
    }

    @Test fun silenceIsNotAnInventedOutgoingMessage() {
        val decisions = JSONArray().put(JSONObject()
            .put("at", now.toString()).put("selected", "silent")
            .put("succeeded", false).put("reason", "想再等等")
            .put("outcome", "角色选择保持安静"))
        val memory = CharacterActionFeedback.recent(decisions, now)
        assertTrue(memory.contains("没有执行外部动作"))
        assertTrue(memory.contains("想再等等"))
    }

    @Test fun oldActionsDoNotDominateCurrentDecisions() {
        val decisions = JSONArray().put(JSONObject()
            .put("at", now.minusSeconds(3L * 24 * 3600).toString())
            .put("selected", "message").put("outcome", "旧发送"))
        assertEquals("", CharacterActionFeedback.recent(decisions, now))
    }
}
