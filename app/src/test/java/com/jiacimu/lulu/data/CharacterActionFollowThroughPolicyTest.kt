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
    @Test fun originalChosenStepsAreNeverReplacedByASecondModelDecision() {
        val pending = listOf("想去查一点资料", "想主动关心对方")
        assertTrue(CharacterActionFollowThroughPolicy.shouldReview(
            silent = true, choices = pending, key = "wish", previousKey = "",
            nowMs = 360_000L, previousAtMs = 0L, alreadyChoseSteps = false))
        assertFalse(CharacterActionFollowThroughPolicy.shouldReview(
            silent = true, choices = pending, key = "wish", previousKey = "",
            nowMs = 360_000L, previousAtMs = 0L, alreadyChoseSteps = true))
        assertFalse(CharacterActionFollowThroughPolicy.shouldReview(
            silent = false, choices = pending, key = "wish", previousKey = "",
            nowMs = 360_000L, previousAtMs = 0L))
    }

    @Test fun severalFreshThoughtsCannotCrowdOutUntriedPersistentWishes() {
        val thoughts = org.json.JSONArray()
        repeat(9) { i -> thoughts.put(JSONObject()
            .put("impulse", "刚想到的新念头$i")) }
        val motives = org.json.JSONArray().put(JSONObject()
            .put("id", "wish-old").put("aim", "一直想去搜索一段历史")
            .put("status", "active").put("nextStep", "核对史料来源"))
        val inner = JSONObject().put("thoughts", thoughts).put("motives", motives)
        val choices = CharacterActionFollowThroughPolicy.pendingChoices(
            inner, null, null, "刚刚又想到别的事情")
        assertEquals(5, choices.size)
        assertTrue(choices.first().contains("刚想到的新念头0"))
        assertTrue("the old motive must reach the role's review",
            choices.any { it.contains("一直想去搜索一段历史") })
        assertFalse("this policy only supplies choices; it never chooses a tool",
            CharacterActionFollowThroughPolicy.shouldReview(
                silent = false, choices = choices, key = "a", previousKey = "", nowMs = 10L, previousAtMs = 0L))
    }

    @Test fun secondaryReviewReusesPrimaryAttentionInsteadOfReopeningAnOldQueue() {
        val primary = listOf("persisted:recent", "persisted:due", "persisted:share",
            "persisted:curiosity", "persisted:concern", "persisted:research")
        val extras = listOf("new thought", "new thought 2", "new thought 3")
        val result = CharacterActionFollowThroughPolicy.unifiedReviewChoices(
            extras, primary, "legacy intention")
        assertEquals(8, result.size)
        assertEquals("new thought", result.first())
        assertEquals(primary, result.subList(1, 7))
        assertEquals("legacy intention", result.last())
        assertFalse(result.contains("new thought 2"))
        assertEquals(result, CharacterActionFollowThroughPolicy.unifiedReviewChoices(
            extras, primary, "legacy intention"))
        assertEquals(emptyList<String>(),
            CharacterActionFollowThroughPolicy.unifiedReviewChoices(extras, primary, limit = 0))
        assertEquals(listOf("new thought", "persisted:recent"),
            CharacterActionFollowThroughPolicy.unifiedReviewChoices(
                extras, listOf("", "persisted:recent", "persisted:recent"), limit = 2))
        assertFalse(CharacterActionFollowThroughPolicy.shouldReview(
            silent = false, choices = result, key = "key", previousKey = "",
            nowMs = 10L, previousAtMs = 0L))
    }

    @Test fun pausedReleasedAndAlreadyAttemptedMotivesDoNotBecomeFreshPromises() {
        val inner = JSONObject("""{"motives":[{"aim":"继续阅读小说","status":"paused"},{"aim":"把书中问题查清楚","op":"release"},{"aim":"看看下一章节内容","status":"active","outcomes":[{"summary":"已阅读"}]}]}""")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "").isEmpty())
        assertEquals(listOf("继续等她回复"),
            CharacterActionFollowThroughPolicy.pendingChoices(null, null, null, "继续等她回复"))
    }
    @Test fun modelWrittenShortOrSolitaryImpulseIsNotReclassifiedByKeywords() {
        for (thought in listOf("想独处", "等她", "嗯", "待会再想", "了解她是什么样的人")) {
            assertEquals(thought, listOf(thought),
                CharacterActionFollowThroughPolicy.pendingChoices(null, null, null, thought))
        }
        val inner = JSONObject("""{"thoughts":[{"impulse":"先休息，再想想"}]}""")
        assertTrue(CharacterActionFollowThroughPolicy.pendingChoices(inner, null, null, "")
            .contains("先休息，再想想"))
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
