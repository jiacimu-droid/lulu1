package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CharacterCausalActionPolicyTest {
    @Test fun actionPhaseCannotInventNewFeelingsAfterAppraisalIsCommitted() {
        val raw = JSONObject()
            .put("action", "message")
            .put("text", "你刚才是不是没听见我说话？")
            .put("reason", "我还想确认一下上一通电话")
            .put("motiveId", "persisted-motive")
            .put("innerThought", "我好委屈")
            .put("inner_voice", "模型偷偷重写")
            .put("innerThoughtBasis", JSONObject().put("focus", "假状态"))
            .put("innerLife", JSONObject().put("emotion", JSONObject().put("feeling", "编造的悲伤")))
            .put("afterglow", JSONObject().put("feeling", "后补情绪"))
            .put("mood", "伤心")
            .put("intention", JSONObject().put("aim", "先选动作再编愿望"))
        val decided = CharacterCausalActionPolicy.actionOnly(raw, alreadyAppraised = true)
        assertEquals("message", decided.optString("action"))
        assertEquals("persisted-motive", decided.optString("motiveId"))
        assertEquals("我还想确认一下上一通电话", decided.optString("reason"))
        assertEquals("你刚才是不是没听见我说话？", decided.optString("text"))
        for (key in listOf("innerLife", "innerThought", "inner_voice",
                "innerThoughtBasis", "afterglow", "mood", "intention")) {
            assertFalse("Action phase must not re-author the cause: $key", decided.has(key))
            assertTrue("Original is not mutated: $key", raw.has(key))
        }
    }

    @Test fun ordinaryOneCallDecisionStillSupportsPrivateState() {
        val proposal = JSONObject().put("innerLife", JSONObject()
            .put("emotion", JSONObject().put("feeling", "高兴")))
        val same = CharacterCausalActionPolicy.actionOnly(proposal, alreadyAppraised = false)
        assertEquals("高兴", same.optJSONObject("innerLife")
            ?.optJSONObject("emotion")?.optString("feeling"))
    }

    @Test fun actionPromptNamesExactSourceAndPermitsSilence() {
        val text = CharacterCausalActionPolicy.followThroughInstruction("interaction-call-end-1")
        assertTrue(text.contains("interaction-call-end-1"))
        assertTrue(text.contains("motiveId"))
        assertTrue(text.contains("silent"))
        assertTrue(text.contains("保存"))
    }
}
