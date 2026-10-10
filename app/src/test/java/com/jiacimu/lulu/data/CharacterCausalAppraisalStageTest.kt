package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CharacterCausalAppraisalStageTest {
    private val now = Instant.parse("2026-10-11T15:30:00Z")

    private fun event(
        id: String, content: String, secondsAgo: Long = 0L,
        source: String = InteractionSignalBridge.SOURCE,
        evidence: EventEvidenceKind = EventEvidenceKind.Observation,
    ) = SharedTimelineEvent(
        id = id, characterId = "character-1", channel = "电话互动",
        speaker = "互动记录", content = content, occurredAt = now.minusSeconds(secondsAgo),
        source = source, evidenceKind = evidence,
    )

    @Test fun hangupIsSalientButNoiseAndUnverifiedModelSpeechAreNot() {
        val realHangup = event("interaction-call-end-11", "用户点击挂断通话")
        val pulse = event("call-silence-11-one-minute", "没有确认新增语音")
        val invented = event("interaction-call-end-model", "他猜想你挂断",
            evidence = EventEvidenceKind.CharacterStatement)
        val unrelated = event("interaction-call-end-not-from-device", "模型生成的故事",
            source = "novel")
        assertTrue(CharacterCausalAppraisalStage.eligible(realHangup))
        assertFalse(CharacterCausalAppraisalStage.eligible(pulse))
        assertFalse(CharacterCausalAppraisalStage.eligible(invented))
        assertFalse(CharacterCausalAppraisalStage.eligible(unrelated))
    }

    @Test fun newestRealInteractionGetsItsOwnStageBeforeAction() {
        val older = event("interaction-call-end-old", "第一次通话结束", secondsAgo = 120)
        val newer = event("interaction-call-end-new", "第二次通话结束", secondsAgo = 5)
        val ignored = event("call-silence-aaa", "正在通话中", secondsAgo = 1)
        assertEquals(newer.id,
            CharacterCausalAppraisalStage.latestPending(listOf(older, ignored, newer))?.id)
        assertNull(CharacterCausalAppraisalStage.latestPending(listOf(ignored)))
    }

    @Test fun modelInstructionRequiresAppraisalBeforeActionWithoutAssumingUserMood() {
        val instruction = CharacterCausalAppraisalStage.instruction()
        assertTrue(instruction.contains("下一步外部行为决定之前"))
        assertTrue(instruction.contains("不能断言用户生气"))
        assertTrue(instruction.contains("至少保留 appraisal.meaning"))
    }
}
