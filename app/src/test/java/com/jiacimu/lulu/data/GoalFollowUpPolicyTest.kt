package com.jiacimu.lulu.data

import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class GoalFollowUpPolicyTest {
    private val now = Instant.parse("2026-10-15T09:00:00Z")

    @Test fun realActionIsNotGoalCompletionAndOnlyOneReviewIsDue() {
        assertEquals(now.plusSeconds(300), GoalFollowUpPolicy.initialReview(
            JSONObject().put("nextStep", "好好解释自己刚才伤人的话"), now))
        assertNull(GoalFollowUpPolicy.initialReview(JSONObject(), now))
        assertEquals(now.plusSeconds(300), GoalFollowUpPolicy.afterReceipt("message", true, 1, now))
        assertNull(GoalFollowUpPolicy.afterReceipt("message", true, 2, now))
        assertNull(GoalFollowUpPolicy.afterReceipt("message", false, 0, now))
        assertNull(GoalFollowUpPolicy.afterReceipt("read_book", true, 0, now))
    }

    @Test fun staleDeadlineOrPauseCannotTriggerOldPaidReview() {
        val goal = JSONObject().put("createdAt", "goal-123").put("stage", "following_up")
            .put("nextReviewAt", now.toString())
        assertTrue(GoalFollowUpPolicy.reviewMatches(goal, "goal-123", now.toEpochMilli(), now))
        assertFalse(GoalFollowUpPolicy.reviewMatches(goal, "different", now.toEpochMilli(), now))
        assertFalse(GoalFollowUpPolicy.reviewMatches(goal, "goal-123", now.plusSeconds(1).toEpochMilli(), now))
        goal.put("stage", "paused")
        assertFalse(GoalFollowUpPolicy.reviewMatches(goal, "goal-123", now.toEpochMilli(), now))
    }

    @Test fun twoUnansweredContactsBlockMoreUnsolicitedMessagesNotPrivateActivity() {
        val goal = JSONObject().put("unansweredContactAttempts", 2)
        assertTrue(GoalFollowUpPolicy.shouldBlockContact(goal, "send_private_message"))
        assertFalse(GoalFollowUpPolicy.shouldBlockContact(goal, "read_book"))
        goal.put("lastUserEvidenceId", "new-reply")
        assertFalse(GoalFollowUpPolicy.shouldBlockContact(goal, "send_private_message"))
    }

    @Test fun expiryDecisionCoalescesSameFiveMinuteGoalCheck() {
        assertTrue(GoalFollowUpPolicy.isCoveredByOnlineExpiry(
            now, now.plusSeconds(5), now))
        assertFalse(GoalFollowUpPolicy.isCoveredByOnlineExpiry(
            now, now.plusSeconds(120), now))
        assertFalse(GoalFollowUpPolicy.isCoveredByOnlineExpiry(now, null, now))
    }

    @Test fun explicitSpaceMustBeRespectedWithoutInferringItFromSilence() {
        assertTrue(GoalFollowUpPolicy.hasRequestedSpace("先别联系我"))
        assertTrue(GoalFollowUpPolicy.hasRequestedSpace("让我静静"))
        assertFalse(GoalFollowUpPolicy.hasRequestedSpace("嗯"))
        assertFalse(GoalFollowUpPolicy.hasRequestedSpace("我没有回复"))
    }
}
