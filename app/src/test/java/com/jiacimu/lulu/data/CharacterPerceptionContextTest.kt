package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterPerceptionContextTest {
    @Test fun personalReceiptsEnterPerceptionWithoutMistakingRoutineMovementOrClaimsForNewExperience() {
        val now = Instant.parse("2026-10-16T12:00:00Z")
        fun receipt(tool: String, action: String = "", status: String = "succeeded") = org.json.JSONObject()
            .put("tool", tool).put("status", status)
            .put("fingerprint", "$tool:" + org.json.JSONObject().put("worldAction", action))
            .put("result", org.json.JSONObject().put("success", status == "succeeded")
                .put("summary", "实际结算840分，未达到开局900分目标").put("evidenceId", "game-fact").toString()).toString()
        val game = SharedTimelineEvent("real-tool", "role", "实际工具执行", "执行器", receipt("play_solo_game"), now,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        val read = game.copy(id = "read", source = "reading:real", content = "真实正文进度",
            evidenceKind = EventEvidenceKind.Observation)
        val move = game.copy(id = "move", content = receipt("digital_world_action", "go_home"))
        val thinking = game.copy(id = "not-done", content = receipt("play_solo_game", status = "running"))
        val claim = game.copy(id = "claim", source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        assertEquals(listOf("real-tool", "read"), CharacterPerceptionContext.selectRecent(
            listOf(game, read, move, thinking, claim), now).map { it.id })
        assertTrue(CharacterPerceptionContext.stimulus(game).description.contains("840分"))
        assertEquals("game-fact", CharacterPerceptionContext.outcomeEvidenceId(game.source, game.content))
        assertNull(CharacterPerceptionContext.personalOutcome("message", game.content))
    }
    @Test fun observedCallEndIsAnEventNotAnInferenceAboutUserEmotion() {
        val now = Instant.parse("2026-10-11T00:00:00Z")
        val call = SharedTimelineEvent(
            "interaction-call-end-123", "role", "电话互动", "通话操作",
            InteractionSignalBridge.callEndDescription(
                endedByCharacter = false, elapsedSeconds = 180,
                confirmedUserSpeechCount = 0, secondsSinceConfirmedSpeech = 180,
            ),
            now.minusSeconds(20), source = InteractionSignalBridge.SOURCE,
            evidenceKind = EventEvidenceKind.Observation,
        )
        val assertedEmotion = call.copy(
            id = "model-made-story", content = "你肯定生气了",
            evidenceKind = EventEvidenceKind.CharacterStatement,
        )
        val actual = CharacterPerceptionContext.selectRecent(listOf(call, assertedEmotion), now)
        assertEquals(listOf(call.id), actual.map { it.id })
        assertEquals(setOf("user"), CharacterPerceptionContext.stimulus(call).socialIds)
        assertTrue(actual.single().content.contains("没有成功收到用户的语音转写"))
        assertTrue(actual.single().content.contains("不能由挂断单独推断"))
    }

    @Test fun connectedVideoReceiptsPersistWithoutClaimingAccessToTheUsersCamera() {
        val app = org.robolectric.RuntimeEnvironment.getApplication()
        UserProfileContext.initialize(app)
        SharedExperienceTimeline.initialize(app)
        val role = "video-evidence-" + java.util.UUID.randomUUID()
        val call = "connected-" + java.util.UUID.randomUUID()
        val chosenId = "interaction-call-video-selected-$call"
        val renderedId = "interaction-call-video-rendered-$call"
        try {
            InteractionSignalBridge.recordVideoSelected(role, call)
            val onlySelected = UserProfileContext.live2dCallContext(role)
            assertFalse(onlySelected.contains("那次角色自己的 Live2D 形象确实成功显示"))
            assertNotNull(SharedExperienceTimeline.eventsByIds(role, listOf(chosenId)).singleOrNull())
            InteractionSignalBridge.recordVideoRendered(role, call)
            InteractionSignalBridge.recordVideoRendered(role, call)
            val verified = SharedExperienceTimeline.eventsByIds(role, listOf(renderedId))
            assertEquals(1, verified.size)
            assertEquals(EventEvidenceKind.Observation, verified.single().evidenceKind)
            val prompt = UserProfileContext.live2dCallContext(role)
            assertTrue(prompt.contains("角色自己的 Live2D 形象确实成功显示"))
            assertTrue(prompt.contains("并不意味着角色可以看见用户本人"))
            assertFalse(prompt.contains("我们已经看到了用户真人"))
        } finally {
            SharedExperienceTimeline.deleteEvent(chosenId)
            SharedExperienceTimeline.deleteEvent(renderedId)
        }
    }

    @Test fun witnessedTouchAndVisitSurviveAlongsideNewChatButStaleAndImaginedEventsDoNot() {
        val now = Instant.parse("2026-10-10T06:00:00Z")
        fun event(id: String, age: Long, kind: EventEvidenceKind) = SharedTimelineEvent(
            id, "role", "数字世界·家里", "场景", "共同在场", now.minusSeconds(age),
            source = "digital-world", evidenceKind = kind)
        val events = listOf(event("old", 90_000, EventEvidenceKind.Observation),
            event("claimed-by-model", 2, EventEvidenceKind.CharacterStatement),
            event("world-visit-1", 10, EventEvidenceKind.Observation),
            event("world-touch-1", 1, EventEvidenceKind.Observation))
        val actual = CharacterPerceptionContext.selectRecent(events, now)
        assertEquals(listOf("world-visit-1", "world-touch-1"), actual.map { it.id })
        val combined = PerceptionStimulusResolver.combine(listOf(
            PerceptionStimulus("chat-1", "用户说话", setOf("user"))) +
            actual.map(CharacterPerceptionContext::stimulus))!!
        assertTrue(combined.evidenceId.contains(":world-touch-1:"))
        assertTrue(combined.evidenceId.contains(":chat-1:"))
        assertEquals(setOf("user"), combined.socialIds)
    }
}
