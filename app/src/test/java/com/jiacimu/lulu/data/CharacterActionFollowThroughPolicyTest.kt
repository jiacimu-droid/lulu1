package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterActionFollowThroughPolicyTest {
    @Test fun aReadingImpulseGetsAChoiceWithoutBecomingAnAutomaticRead() {
        val inner = JSONObject("""{"thoughts":[{"thought":"刚才那段没看完","impulse":"继续看看那本书"}]}""")
        val choices = CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "")
        assertEquals(listOf("继续看看那本书"), choices)
        assertTrue(CharacterActionFollowThroughPolicy.shouldReview(true, choices, "new", "old", 1000, 900))
        assertFalse(CharacterActionFollowThroughPolicy.shouldReview(false, choices, "new", "old", 1000, 900))
    }
    @Test fun pausedReleasedAndAlreadyAttemptedMotivesDoNotBecomeFreshPromises() {
        val inner = JSONObject("""{"motives":[{"aim":"继续阅读小说","status":"paused"},{"aim":"把书中问题查清楚","op":"release"},{"aim":"看看下一章节内容","status":"active","outcomes":[{"summary":"已阅读"}]}]}""")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "").isEmpty())
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(null, null, null, "继续等她回复").isEmpty())
    }
    @Test fun chatAndPhoneStructuredConsiderationsEnterTheSameReview() {
        val idea = JSONObject().put("stage", "considering").put("action", "reading")
            .put("whyNow", "想看看昨天那章的后续")
        val choices = CharacterActionFollowThroughPolicy.pendingChoices(null, null, null, "", idea)
        assertTrue(choices.single().contains("reading"))
        idea.put("stage", "released")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(null, null, null, "", idea).isEmpty())
    }

    @Test fun pausedDesireMustNotBeAutoReconsideredAsUnfinishedWork() {
        val goal = JSONObject().put("aim", "想把书读完").put("stage", "paused")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(null, goal, null, "").isEmpty())
        goal.put("stage", "blocked")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(null, goal, null, "").isNotEmpty())
    }

    @Test fun identicalUnresolvedWishDoesNotCreateARetryLoop() {
        val choices = listOf("去看看书中那条线索")
        assertFalse(CharacterActionFollowThroughPolicy.shouldReview(true, choices, "same", "same", 299999, 0))
        assertTrue(CharacterActionFollowThroughPolicy.shouldReview(true, choices, "same", "same", 300000, 0))
    }
    @Test fun longTermRelationshipWishIsOfferedAsAChoiceWithItsRealIdAndNextStep() {
        val goal = JSONObject().put("createdAt", "wish-id").put("aim", "了解她是什么样的人")
            .put("motive", "想从真实相处认识她").put("nextStep", "聊到她喜欢的事时再接话")
        val choices = CharacterActionFollowThroughPolicy.pendingChoices(null, goal, null, "")
        assertTrue(choices.single().contains("intentionId=wish-id"))
        assertTrue(choices.single().contains("聊到她喜欢的事时再接话"))
        goal.put("stage", "paused")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(null, goal, null, "").isEmpty())
    }

    @Test fun failedActionReceiptRemainsAChoiceButNeverBecomesAnAutomaticExecution() {
        val inner = JSONObject("""{"motives":[{"id":"m1","aim":"查清书里的疑问","status":"active",
            "lastAttemptOutcome":"needs_review","outcomes":[{"success":false,"summary":"搜索超时"}]}]}""")
        val choices = CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "")
        assertEquals(1, choices.size)
        assertTrue(choices.single().contains("查清书里的疑问"))
        assertTrue(choices.single().contains("搜索超时"))
        assertTrue(choices.single().contains("暂缓"))
    }

    @Test fun threeConsecutiveFailuresDoNotCreateUnlimitedPaidFollowUps() {
        val inner = JSONObject("""{"motives":[{"id":"m1","aim":"研究一本书","status":"active",
            "lastAttemptOutcome":"needs_review","outcomes":[{"success":false},{"success":false},{"success":false}]}]}""")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "").isEmpty())
        // The active motive itself remains in long-term memory. Only the
        // automatic silent-turn review is suppressed pending new evidence.
    }

    @Test fun aSuccessfulAttemptIsNotMistakenForAnUntriedImpulse() {
        val inner = JSONObject("""{"motives":[{"aim":"看完一本书","status":"active",
            "lastAttemptOutcome":"success","outcomes":[{"success":true,"summary":"读完第一章"}]}]}""")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "").isEmpty())
    }

}
