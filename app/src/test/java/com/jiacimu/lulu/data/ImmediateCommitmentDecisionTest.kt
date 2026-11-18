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
class ImmediateCommitmentDecisionTest {
    @Test fun aContextualPhoneFollowUpCanRequestRepairButCannotInventAnAction() {
        val draft = "我这就退回去往下滑，重新给你找一篇。"
        assertTrue(ImmediateCommitmentDecision.mayHaveOmittedPhoneAction(
            draft, "刚才选的是小红书的一篇帖子；用户说换点别的。"))
        assertFalse(ImmediateCommitmentDecision.mayHaveOmittedPhoneAction(
            "我继续听你讲。", "刚才提到小红书。"))
        assertNull(ImmediateCommitmentDecision.repairStep(
            JSONObject("""{"tool":"background_phone","args":{"appName":"小红书"}}""")))
        assertNull(ImmediateCommitmentDecision.repairStep(
            JSONObject("""{"tool":"background_phone","args":{"action":"tap","x":230,"y":340}}""")))
        assertNotNull(ImmediateCommitmentDecision.repairStep(
            JSONObject("""{"tool":"background_phone","args":{"action":"launch","appName":"小红书"}}""")))
    }

    @Test fun immediateCommitmentNeedsAnActualIndexedFirstStep() {
        val decision = ImmediateCommitmentDecision.repairedChange(
            JSONObject("""{"goal":"找另一篇帖子","completionCondition":"已把新的帖子链接发到私聊"}"""),
            stepIndex = 1,
        )!!
        val steps = JSONArray("""[
          {"tool":"background_phone","args":{"action":"observe"}},
          {"tool":"plan_personal_activity","args":{"dueAt":"2026-11-18T13:00:00Z"}}
        ]""")
        assertFalse(ImmediateCommitmentDecision.hasFirstAction(decision, "", JSONObject(), steps))
        decision.put("firstStep", 0)
        assertTrue(ImmediateCommitmentDecision.hasFirstAction(decision, "", JSONObject(), steps))
        decision.put("firstStep", -1)
        assertTrue(ImmediateCommitmentDecision.hasFirstAction(decision,
            "background_phone", JSONObject("""{"action":"observe"}"""), steps))
        assertFalse(ImmediateCommitmentDecision.hasFirstAction(decision, "", JSONObject(), steps))
    }

    @Test fun anImmediateResponsibilitySurvivesPersistenceAndIsOnlyReviewedWhileOpen() {
        val now = Instant.parse("2026-11-18T12:00:00Z")
        val task = CommitmentTask(characterId = "role", goal = "替她找到另一篇帖子并分享",
            status = CommitmentTaskStatus.WaitingForFeedback, startNow = true)
        val restored = decodeCommitmentTasks(encodeCommitmentTasks(listOf(task))).single()
        assertTrue(restored.startNow)
        assertTrue(ImmediateCommitmentDecision.readyToReview(restored, now.plusSeconds(301)))
        assertFalse(ImmediateCommitmentDecision.readyToReview(
            restored.copy(status = CommitmentTaskStatus.Completed), now))
        assertFalse(ImmediateCommitmentDecision.readyToReview(
            restored.copy(status = CommitmentTaskStatus.Cancelled), now))
        assertFalse(ImmediateCommitmentDecision.readyToReview(
            restored.copy(nextCheckAt = now.plusSeconds(60)), now))
        assertFalse(ImmediateCommitmentDecision.readyToReview(restored.copy(startNow = false), now))
    }
}
