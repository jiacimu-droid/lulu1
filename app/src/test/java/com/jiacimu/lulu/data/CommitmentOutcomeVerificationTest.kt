package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CommitmentOutcomeVerificationTest {
    private val at = Instant.parse("2026-10-26T01:00:00Z")
    private val task = CommitmentTask(characterId = "roleA", goal = "监督按时入睡",
        createdAt = at.minusSeconds(1800), completionCondition = "确实开始睡眠")

    private fun event(id: String, kind: EventEvidenceKind, source: String = "message",
        role: String = "roleA", occurredAt: Instant = at) =
        SharedTimelineEvent(id, role, "私聊", "主人", "我已经睡了", occurredAt,
            source = source, evidenceKind = kind)

    @Test fun claimedSleepAndSilenceAreNotProof() {
        assertFalse(CommitmentOutcomeVerification.evaluate(task, listOf("s"), "主人说睡了没有再回复",
            listOf(event("s", EventEvidenceKind.UserStatement))).accepted)
        assertFalse(CommitmentOutcomeVerification.evaluate(task, listOf("s"), "没有更多反馈",
            emptyList()).accepted)
    }

    @Test fun passiveDeviceCueCannotProveExternalOutcome() {
        val cue = event("device", EventEvidenceKind.Observation, "commitment-follow-up")
        assertFalse(CommitmentOutcomeVerification.evaluate(task, listOf("device"),
            "手机没有交互", listOf(cue)).accepted)
    }

    @Test fun executedToolSupportsActionOnlyIfActuallySuccessful() {
        val completed = event("done", EventEvidenceKind.ToolResult, "tool-router")
            .copy(content = """{"status":"completed","result":"{\"success\":true}","tool":"create_alarm"}""")
        assertTrue(CommitmentOutcomeVerification.evaluate(task.copy(verificationMode = "delivery"),
            listOf("done"), "真实工具确实成功登记了闹钟", listOf(completed)).accepted)
        assertFalse(CommitmentOutcomeVerification.evaluate(task.copy(verificationMode = "delivery"),
            listOf("claim"), "角色说已创建闹钟", listOf(event("claim",
                EventEvidenceKind.CharacterStatement))).accepted)
    }

    @Test fun successfulToolCannotProveSeparateRealWorldOutcome() {
        val receipt = event("alarm-success", EventEvidenceKind.ToolResult, "tool-router")
            .copy(content = """{"status":"succeeded","result":"{\\"success\\":true}","tool":"create_alarm"}""")
        val checked = CommitmentOutcomeVerification.evaluate(
            task, listOf("alarm-success"),
            "已成功设置闹钟，主人肯定已经入睡",
            listOf(receipt),
        )
        assertFalse("Tool execution is not sleep observation", checked.accepted)
    }

    @Test fun roleOwnershipAndChronologyAreRequired() {
        assertFalse(CommitmentOutcomeVerification.evaluate(task, listOf("x"), "其他角色看见了",
            listOf(event("x", EventEvidenceKind.Observation, "device", "roleB"))).accepted)
        assertFalse(CommitmentOutcomeVerification.evaluate(task, listOf("x"), "过去看见的东西",
            listOf(event("x", EventEvidenceKind.Observation, "device",
                occurredAt = at.minusSeconds(4000)))).accepted)
    }

    @Test fun subjectiveExperienceCanUseUserStatementOnlyWhenGoalIsSubjective() {
        assertTrue(CommitmentOutcomeVerification.evaluate(task.copy(verificationMode = "subjective_report"),
            listOf("s"), "本人正在评价自己是否觉得舒服", listOf(event("s",
                EventEvidenceKind.UserStatement))).accepted)
    }

    @Test fun followUpKindDoesNotMisrouteWakeRetries() {
        assertTrue(task.copy(attemptCount = 1, followUpKind = "verification").isOutcomeReview())
        assertFalse(task.copy(attemptCount = 1, followUpKind = "retry").isOutcomeReview())
        assertFalse(task.copy(attemptCount = 0, followUpKind = "verification").isOutcomeReview())
    }

    @Test fun persistedRecordsRetainEvidenceAndReviewKinds() {
        val old = task.copy(followUpKind = "verification", verificationMode = "delivery",
            completionEvidenceIds = listOf("ok"), lastVerificationReason = "actual result")
        val restored = decodeCommitmentTasks(encodeCommitmentTasks(listOf(old))).single()
        assertEquals(old.completionEvidenceIds, restored.completionEvidenceIds)
        assertEquals(old.followUpKind, restored.followUpKind)
        assertEquals(old.verificationMode, restored.verificationMode)
    }
}
