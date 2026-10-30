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
            .copy(content = """{"status":"succeeded","result":"{\"success\":true}","tool":"create_alarm"}""")
        val checked = CommitmentOutcomeVerification.evaluate(
            task, listOf("alarm-success"),
            "已成功设置闹钟，主人肯定已经入睡",
            listOf(receipt),
        )
        assertTrue("Receipt fixture must prove the tool actually succeeded",
            CommitmentOutcomeVerification.evaluate(
                task.copy(verificationMode = "delivery"),
                listOf("alarm-success"), "闹钟已经真实保存成功", listOf(receipt)
            ).accepted)
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

    @Test fun evidenceCandidatesUseTheSameProvenanceGateAsVerification() {
        val action = event("executed", EventEvidenceKind.ToolResult, "tool-router")
            .copy(content = """{"status":"succeeded","result":"{\"success\":true}","tool":"start_call"}""")
        val observation = event("observed", EventEvidenceKind.Observation, "screen-consent")
            .copy(content = "本人真实看到一帧屏幕，仍需判断是否与目标有关")
        val followUp = event("review", EventEvidenceKind.Observation, "commitment-follow-up")
        val userClaim = event("claim", EventEvidenceKind.UserStatement)
        val foreign = event("foreign", EventEvidenceKind.Observation, "device", "roleB")
        val old = event("old", EventEvidenceKind.Observation, "device",
            occurredAt = at.minusSeconds(4000))
        val window = listOf(action, observation, followUp, userClaim, foreign, old)

        val outcome = CommitmentOutcomeVerification.candidateContext(task, window)
        assertTrue(outcome.contains("id=observed"))
        assertFalse(outcome.contains("id=executed"))
        assertFalse(outcome.contains("id=claim"))
        assertFalse(outcome.contains("id=review"))
        assertFalse(outcome.contains("id=foreign"))
        assertFalse(outcome.contains("id=old"))
        assertTrue(CommitmentOutcomeVerification.evaluate(task, listOf("observed"),
            "这帧确实是设备观察但仍需判断目标是否达成", listOf(observation)).accepted)

        val delivery = CommitmentOutcomeVerification.candidateContext(
            task.copy(verificationMode = "delivery"), window)
        assertTrue(delivery.contains("id=executed"))
        assertFalse(delivery.contains("id=observed"))

        val subjective = CommitmentOutcomeVerification.candidateContext(
            task.copy(verificationMode = "subjective_report"), window)
        assertTrue(subjective.contains("id=claim"))
        assertTrue(subjective.contains("id=observed"))
    }

    @Test fun noQualifiedCandidateMustNotBeAdvertisedAsCompletionProof() {
        val action = event("only-action", EventEvidenceKind.ToolResult, "tool-router")
            .copy(content = """{"status":"succeeded","result":"{\"success\":true}","tool":"create_alarm"}""")
        val shown = CommitmentOutcomeVerification.candidateContext(task, listOf(action))
        assertTrue(shown.contains("没有类型合格的结案候选"))
        assertFalse(shown.contains("id=only-action"))
        assertFalse(CommitmentOutcomeVerification.evaluate(task, listOf("only-action"),
            "已经发出了本机闹钟所以人肯定睡了", listOf(action)).accepted)
    }

    @Test fun followUpKindDoesNotMisrouteWakeRetries() {
        assertTrue(task.copy(attemptCount = 1, followUpKind = "verification").isOutcomeReview())
        assertFalse(task.copy(attemptCount = 1, followUpKind = "retry").isOutcomeReview())
        assertTrue(task.copy(attemptCount = 0, followUpKind = "verification").isOutcomeReview())
    }

    @Test fun wakeRoundHistoryPersistsAndLegacyTasksStayCompatible() {
        val wake = task.copy(goal = "明天早上叫醒我", deliveryAction = "start_call",
            attemptCount = 2, followUpKind = "retry",
            wakeAttemptHistory = listOf("第一轮铃声已启动", "第一轮说话已播完", "第二轮角色选择再说话"))
        val restored = decodeCommitmentTasks(encodeCommitmentTasks(listOf(wake))).single()
        assertEquals(wake.wakeAttemptHistory, restored.wakeAttemptHistory)
        assertEquals(2, restored.attemptCount)
        assertEquals("retry", restored.followUpKind)
        assertEquals(emptyList<String>(), decodeCommitmentTasks(
            encodeCommitmentTasks(listOf(task))).single().wakeAttemptHistory)
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
