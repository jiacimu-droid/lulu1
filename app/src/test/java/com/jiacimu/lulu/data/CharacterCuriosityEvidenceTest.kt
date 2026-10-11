package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterCuriosityEvidenceTest {
    private fun event(id: String, channel: String, kind: EventEvidenceKind) =
        SharedTimelineEvent(id = id, characterId = "test-role", channel = channel,
            speaker = "角色", content = "真实发生并已记录的事件",
            occurredAt = Instant.parse("2026-10-11T02:00:00Z"), evidenceKind = kind)

    @Test fun quietDayIdeaCanReferenceOneExistingWorldExperience() {
        val real = event("real-1", "独自阅读", EventEvidenceKind.ToolResult)
        val proposal = JSONObject().put("sourceEvidenceId", "real-1")
        assertEquals(real, CharacterCuriosityEvidence.findOriginal(proposal, listOf(real)))
        assertNull(CharacterCuriosityEvidence.findOriginal(
            proposal.put("sourceEvidenceId", "invented"), listOf(real)))
    }

    @Test fun thoughtsAndCharacterClaimsDoNotBecomeObservedEvents() {
        val claimed = event("said", "私聊", EventEvidenceKind.CharacterStatement)
        val imagined = event("imagined", "数字世界·生活片段", EventEvidenceKind.Inference)
        val user = event("user", "私聊", EventEvidenceKind.UserStatement)
        val actual = event("actual", "数字世界事件", EventEvidenceKind.Observation)
        val candidates = listOf(claimed, imagined, user, actual)
        listOf("said", "imagined", "user").forEach { candidate ->
            assertNull(CharacterCuriosityEvidence.findOriginal(
                JSONObject().put("sourceEvidenceId", candidate), candidates))
        }
        assertEquals(actual, CharacterCuriosityEvidence.findOriginal(
            JSONObject().put("sourceEvidenceId", "actual"), candidates))
    }
}
