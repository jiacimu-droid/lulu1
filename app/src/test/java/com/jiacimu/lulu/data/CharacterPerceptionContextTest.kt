package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CharacterPerceptionContextTest {
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
