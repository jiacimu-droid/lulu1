package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class PrimaryTurnCommitmentWireTest {
    @Test fun primaryPerceptionRetainsBothTypedChangesAndEmptyDecision() {
        val model = JSONObject().put("action", "message")
            .put("text", "好的，我会照顾好之前答应的事")
            .put("commitmentChanges", JSONArray().put(
                JSONObject().put("action", "create")
                    .put("goal", "明天核实学习进度")
                    .put("promiseKind", "long_term_supervision")))
            .put("lifeChanges", JSONArray())
        val normalized = CharacterCausalActionPolicy.actionOnly(
            AutonomousActionWireProtocol.normalize(model), alreadyAppraised = false)
        assertEquals(1, normalized.optJSONArray("commitmentChanges")!!.length())
        assertEquals(0, normalized.optJSONArray("lifeChanges")!!.length())
        assertTrue(onlineTurnCommitmentInstruction().contains("没有实际新增"))
        assertTrue(onlineTurnLifeInstruction().contains("evidenceQuote"))
    }
}
