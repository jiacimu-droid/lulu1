package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousActionTraceTest {
    @Test fun sameActionDifferentPlaceIsNotTheSameBehavior() {
        val a = AutonomousActionTrace.signature("digital_world", worldAction = "visit_public_place",
            destination = "shared:courtyard")
        val b = AutonomousActionTrace.signature("digital_world", worldAction = "visit_public_place",
            destination = "shared:reading_lounge")
        assertNotEquals(a, b)
        assertEquals(a, AutonomousActionTrace.signature("digital_world",
            worldAction = "visit_public_place", destination = "shared:courtyard"))
    }

    @Test fun feedbackShowsRepetitionButDoesNotForbidLegitimateExploration() {
        val at = Instant.parse("2026-10-11T02:00:00Z")
        val decision = JSONObject().put("at", at.toString())
            .put("selected", "digital_world").put("signature", "digital_world/visit_cloud_meadow")
            .put("succeeded", true).put("outcome", "真的到了云眠原")
        val history = JSONArray().put(decision).put(JSONObject(decision.toString()))
        val rendered = AutonomousActionTrace.render(history, at.plusSeconds(300))
        assertTrue(rendered.contains("同一具体动作近期出现过多次"))
        assertTrue(rendered.contains("可以继续"))
    }

    @Test fun sharedReceiptsRecognizePrivateCharacterVoiceButNeverUserStatements() {
        val at = Instant.parse("2026-10-11T09:00:00Z")
        fun event(id: String, speaker: String, kind: EventEvidenceKind, session: String = "") =
            SharedTimelineEvent(id, "role-a", "私聊", speaker, "事件", at,
                sessionId = session, evidenceKind = kind)
        val events = listOf(
            event("old", "角色", EventEvidenceKind.CharacterStatement),
            event("private", "角色", EventEvidenceKind.CharacterStatement, "chat-a"),
            event("user-new", "用户", EventEvidenceKind.UserStatement, "chat-a"))
        assertEquals("private", AutonomousActionTrace.newestExecutedEvidence(
            events, setOf("old"), "江渡", "chat-a"))
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            events, setOf("old", "private"), "江渡", "chat-a"))
        val nicknameEvent = event("group", "群里昵称", EventEvidenceKind.CharacterStatement, "group-b")
        assertEquals("group", AutonomousActionTrace.newestExecutedEvidence(
            listOf(nicknameEvent), emptySet(), "江渡", "group-b"))
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(nicknameEvent), emptySet(), "江渡", "other-group"))
        val receipt = event("tool-result", "执行器", EventEvidenceKind.ToolResult)
        assertEquals("tool-result", AutonomousActionTrace.newestExecutedEvidence(
            listOf(receipt), emptySet(), "江渡"))
    }

    @Test fun oldHistoryDoesNotCreateArtificialPressureToAct() {
        val history = JSONArray().put(JSONObject().put("at", "2026-09-01T00:00:00Z")
            .put("selected", "reading").put("signature", "reading/book-1")
            .put("succeeded", true))
        assertEquals("", AutonomousActionTrace.render(history,
            Instant.parse("2026-10-11T02:00:00Z")))
    }
}
