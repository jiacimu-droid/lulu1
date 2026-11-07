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

    @Test fun quietSpeechWithChosenToolsIsLoggedAsActionNotSilence() {
        val at = Instant.parse("2026-10-26T03:00:00Z")
        val a = JSONArray().put(JSONObject().put("tool", "web_search")
            .put("args", JSONObject().put("query", "感兴趣的甲")))
        val b = JSONArray().put(JSONObject().put("tool", "web_search")
            .put("args", JSONObject().put("query", "感兴趣的乙")))
        val signature = AutonomousActionTrace.selectedStepsSignature(a)
        assertTrue(signature.startsWith("steps/web_search:"))
        assertFalse(signature.contains("感兴趣的甲"))
        assertNotEquals(signature, AutonomousActionTrace.selectedStepsSignature(b))
        val history = JSONArray()
            .put(JSONObject().put("at", at.toString()).put("selected", "silent")
                .put("signature", "silent").put("succeeded", false))
            .put(JSONObject().put("at", at.plusSeconds(5).toString())
                .put("selected", "steps").put("signature", signature)
                .put("succeeded", true).put("outcome", "第1步web_search：完成"))
        val rendered = AutonomousActionTrace.render(history, at.plusSeconds(10))
        assertTrue(rendered.contains("做过 steps/web_search:"))
        assertFalse("Successful selected tools must break the apparent silence streak",
            rendered.contains("连续选择了安静"))
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

    @Test fun consecutiveSilenceRecallsTheOwnReasonAndOfferedIdsWithoutForcingActions() {
        val time = Instant.parse("2026-10-26T04:15:00Z")
        val older = JSONObject().put("at", time.minusSeconds(300).toString())
            .put("selected", "silent").put("reason", "刚想安静看一会儿")
        val last = JSONObject().put("at", time.toString()).put("selected", "silent")
            .put("reason", "我想先查清资料再去联系她")
            .put("attentionOffered", JSONArray().put("好奇:real-question")
                .put("持续目标:goal-123"))
        val rendered = AutonomousActionTrace.render(JSONArray().put(older).put(last),
            time.plusSeconds(30))
        assertTrue(rendered.contains("我想先查清资料再去联系她"))
        assertTrue(rendered.contains("好奇:real-question"))
        assertTrue(rendered.contains("被提供不证明本人已逐条考虑或放弃"))
        assertTrue(rendered.contains("可以是符合本人的选择"))
        assertFalse(rendered.contains("必须立刻执行"))
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
        // A different private conversation of the same role is not this send's receipt.
        val otherChat = event("other-chat", "角色", EventEvidenceKind.CharacterStatement, "chat-b")
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(otherChat), emptySet(), "江渡", "chat-a"))
        assertEquals("other-chat", AutonomousActionTrace.newestExecutedEvidence(
            listOf(otherChat), emptySet(), "江渡", "chat-b"))
        val nicknameEvent = event("group", "群里昵称", EventEvidenceKind.CharacterStatement, "group-b")
        assertEquals("group", AutonomousActionTrace.newestExecutedEvidence(
            listOf(nicknameEvent), emptySet(), "江渡", "group-b"))
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(nicknameEvent), emptySet(), "江渡", "other-group"))
        // Without a trustworthy session id, arbitrary group nicknames cannot
        // be promoted into this character's own completed action.
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(nicknameEvent), emptySet(), "江渡"))
        // A matching session must never turn incoming user speech into delivery.
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(event("same-session-user", "用户", EventEvidenceKind.UserStatement, "group-b")),
            emptySet(), "江渡", "group-b"))
        val receipt = event("tool-result", "执行器", EventEvidenceKind.ToolResult)
        assertEquals("tool-result", AutonomousActionTrace.newestExecutedEvidence(
            listOf(receipt), emptySet(), "江渡"))
        val scopedOtherTool = event("other-tool", "执行器", EventEvidenceKind.ToolResult, "chat-b")
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(scopedOtherTool), emptySet(), "江渡", "chat-a"))
        assertEquals("other-tool", AutonomousActionTrace.newestExecutedEvidence(
            listOf(scopedOtherTool), emptySet(), "江渡", "chat-b"))
        // A sessionless receipt is still eligible for non-chat tools.
        assertEquals("tool-result", AutonomousActionTrace.newestExecutedEvidence(
            listOf(receipt), emptySet(), "江渡", "chat-a"))
    }

    @Test fun newerUnrelatedReceiptCannotHideDeliveredPrimaryMessage() {
        val at = Instant.parse("2026-10-11T09:00:00Z")
        val delivered = SharedTimelineEvent("primary-message", "role-a", "私聊", "角色",
            "已送达", at, sessionId = "chat-a",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val unrelated = SharedTimelineEvent("side-tool", "role-a", "工具", "执行器",
            "副动作完成", at.plusSeconds(1), evidenceKind = EventEvidenceKind.ToolResult)
        val events = listOf(delivered, unrelated)
        // The generic legacy candidate is the newer unrelated tool receipt.
        assertEquals("side-tool", AutonomousActionTrace.newestExecutedEvidence(
            events, emptySet(), "江渡", "chat-a"))
        // The proactive primary action must select its own compatible delivery.
        assertEquals("primary-message", AutonomousActionTrace.newestExecutedEvidence(
            events, emptySet(), "江渡", "chat-a",
            supportsAction = { it.evidenceKind == EventEvidenceKind.CharacterStatement }))
        assertEquals("", AutonomousActionTrace.newestExecutedEvidence(
            listOf(unrelated), emptySet(), "江渡", "chat-a",
            supportsAction = { it.evidenceKind == EventEvidenceKind.CharacterStatement }))
    }

    @Test fun oldHistoryDoesNotCreateArtificialPressureToAct() {
        val history = JSONArray().put(JSONObject().put("at", "2026-09-01T00:00:00Z")
            .put("selected", "reading").put("signature", "reading/book-1")
            .put("succeeded", true))
        assertEquals("", AutonomousActionTrace.render(history,
            Instant.parse("2026-10-11T02:00:00Z")))
    }
}
