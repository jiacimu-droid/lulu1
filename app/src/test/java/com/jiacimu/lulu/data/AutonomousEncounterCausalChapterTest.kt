package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class AutonomousEncounterCausalChapterTest {
    private val start = Instant.parse("2026-11-17T05:10:00Z")
    private val end = start.plusSeconds(70)

    @Test fun authorUsesOnlyOwnVerifiedSourceLinkedVoiceAndPriorDecisions() {
        val mind = JSONObject().put("innerVoices", JSONArray()
            .put(JSONObject().put("evidenceId", "arrival-a")
                .put("occurredAt", start.minusSeconds(30).toString())
                .put("thought", "我是来找主人的，她在哪？"))
            .put(JSONObject().put("evidenceId", "arrival-b")
                .put("occurredAt", start.toString()).put("thought", "另一个人的秘密"))
            .put(JSONObject().put("evidenceId", "meeting-a")
                .put("occurredAt", end.plusSeconds(10).toString())
                .put("thought", "事后才产生的念头")))
            .put("decisions", JSONArray()
                .put(JSONObject().put("at", start.minusSeconds(45).toString())
                    .put("selected", "digital_world").put("reason", "收到到访通知，想去找主人")
                    .put("outcome", "已移动").put("succeeded", true))
                .put(JSONObject().put("at", end.plusSeconds(10).toString())
                    .put("selected", "message").put("reason", "未来才做的选择"))
                .put(JSONObject().put("at", start.toString())
                    .put("causalEvidenceId", "meeting-other").put("selected", "message")
                    .put("reason", "没有本场证据的不相关选择")))
        val text = AutonomousEncounterChapters.roleEvidence(
            mind, setOf("arrival-a", "meeting-a"), start, end)
        assertTrue(text.contains("我是来找主人的"))
        assertTrue(text.contains("收到到访通知，想去找主人"))
        assertFalse(text.contains("另一个人的秘密"))
        assertFalse(text.contains("事后才产生的念头"))
        assertFalse(text.contains("未来才做的选择"))
    }

    @Test fun noMindEvidenceNeverBecomesInventedMotivation() {
        val prose = AutonomousEncounterChapters.roleEvidence(
            JSONObject(), setOf("meeting-a"), start, end)
        assertTrue(prose.contains("不要补造"))
    }
}
