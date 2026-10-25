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
class WanxiangCharacterContinuityTest {
    @Test fun residentAgendaKeepsExplicitContactAndPlaceInExecutorArgs() {
        val proposal = JSONObject()
            .put("targetCharacterId", "resident:friend")
            .put("activityArgs", JSONObject().put("text", "上次那本书看完啦")
                .put("planId", "injected"))
        val args = ResidentDailyDecision.agendaArgs("send_character_message", proposal)
        assertEquals("resident:friend", args.optString("targetCharacterId"))
        assertEquals("上次那本书看完啦", args.optString("text"))
        assertFalse(args.has("planId"))
        assertEquals("injected", proposal.getJSONObject("activityArgs").optString("planId"))

        val visit = JSONObject().put("locationCode", "world-courtyard")
            .put("activityArgs", JSONObject().put("worldAction", "visit_public_place")
                .put("locationCode", "old-location"))
        val normalized = ResidentDailyDecision.agendaArgs("digital_world_action", visit)
        assertEquals("world-courtyard", normalized.optString("locationCode"))
        val unrelated = ResidentDailyDecision.agendaArgs("play_solo_game",
            JSONObject().put("targetCharacterId", "resident:friend")
                .put("activityArgs", JSONObject().put("gameId", "game1")))
        assertFalse(unrelated.has("targetCharacterId"))
        assertEquals("game1", unrelated.optString("gameId"))
    }

    @Test fun residentLifeContextExcludesOtherPeopleDraftPlansAndUnfinishedTools() {
        val now = Instant.parse("2026-10-25T06:00:00Z")
        fun event(id: String, person: String, source: String,
            kind: EventEvidenceKind, body: String = "曾真实经过广场") =
            SharedTimelineEvent(id, person, "数字世界", person, body,
                now, source = source, evidenceKind = kind)
        val ok = JSONObject().put("tool", "digital_world_action")
            .put("status", "succeeded")
            .put("result", JSONObject().put("summary", "到了旧桥").toString()).toString()
        val failed = JSONObject().put("tool", "send_character_message")
            .put("status", "failed")
            .put("result", JSONObject().put("error", "对方已不在联系人内").toString()).toString()
        val actual = ResidentDailyDecision.ownLifeEvidence("resident:one", listOf(
            event("planned", "resident:one", "resident-daily", EventEvidenceKind.CharacterStatement),
            event("pending", "resident:one", "tool-router", EventEvidenceKind.ToolResult,
                JSONObject().put("status", "running").toString()),
            event("foreign", "resident:two", "tool-router", EventEvidenceKind.ToolResult, ok),
            event("real", "resident:one", "tool-router", EventEvidenceKind.ToolResult, ok),
            event("failed", "resident:one", "tool-router", EventEvidenceKind.ToolResult, failed),
            event("invented", "resident:one", "tool-router", EventEvidenceKind.CharacterStatement, ok),
            event("routine", "resident:one", "resident-duty", EventEvidenceKind.Observation),
        ))
        assertEquals(listOf("real", "failed", "routine"), actual.map { it.id })
        assertTrue(ResidentDailyDecision.lifeEvidenceLine(actual.first()).contains("到了旧桥"))
        assertTrue(ResidentDailyDecision.lifeEvidenceLine(actual[1]).contains("对方已不在联系人内"))
    }

    @Test fun sharedPrivateAndVoiceGuidanceAllowsSpontaneousSpeechWithoutInventedMisunderstanding() {
        val private = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PRIVATE_CHAT)
        val phone = CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PHONE)
        assertTrue(private.contains("不为了活人感故意制造误会"))
        assertTrue(private.contains("不在每轮末尾附一段总结"))
        assertTrue(phone.contains("没有听清便承认没听清"))
        assertTrue(phone.contains("实时通话优先低延迟"))
        assertFalse(phone.contains("每次必须"))
    }
}
