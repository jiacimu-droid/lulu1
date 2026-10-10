package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
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
        val touch = event("world-touch-1", "用户碰了一下角色的手",
            source = "meeting")
        val visit = event("world-visit-1", "用户到访角色的家",
            source = "digital-world")
        val fictionalTouch = event("world-touch-imagined", "虚构接触",
            source = "novel")
        assertTrue(CharacterCausalAppraisalStage.eligible(touch))
        assertTrue(CharacterCausalAppraisalStage.eligible(visit))
        assertFalse(CharacterCausalAppraisalStage.eligible(fictionalTouch))
    }

    @Test fun newestRealInteractionGetsItsOwnStageBeforeAction() {
        val older = event("interaction-call-end-old", "第一次通话结束", secondsAgo = 120)
        val newer = event("interaction-call-end-new", "第二次通话结束", secondsAgo = 5)
        val ignored = event("call-silence-aaa", "正在通话中", secondsAgo = 1)
        assertEquals(newer.id,
            CharacterCausalAppraisalStage.latestPending(listOf(older, ignored, newer))?.id)
        assertNull(CharacterCausalAppraisalStage.latestPending(listOf(ignored)))
    }

    @Test fun persistedFeelingAloneDoesNotCountAsCompletedAction() {
        val evidenceId = "interaction-call-end-abc"
        val appraisal = JSONArray().put(JSONObject()
            .put("evidenceId", evidenceId).put("selectedAction", "appraise"))
        assertTrue(CharacterCausalAppraisalStage.hasAppraisalReceipt(appraisal, evidenceId))
        assertFalse(CharacterCausalAppraisalStage.hasDecisionReceipt(JSONArray(), evidenceId))

        val completed = JSONArray().put(JSONObject()
            .put("selected", "silent").put("causalEvidenceId", evidenceId)
            .put("reason", "今天暂时不打扰"))
        assertTrue(CharacterCausalAppraisalStage.hasDecisionReceipt(completed, evidenceId))
        assertFalse(CharacterCausalAppraisalStage.hasDecisionReceipt(completed, "another-call"))
    }

    @Test fun modelInstructionRequiresAppraisalBeforeActionWithoutAssumingUserMood() {
        val instruction = CharacterCausalAppraisalStage.instruction()
        assertTrue(instruction.contains("下一步外部行为决定之前"))
        assertTrue(instruction.contains("不能断言用户生气"))
        assertTrue(instruction.contains("至少保留 appraisal.meaning"))
    }
}
