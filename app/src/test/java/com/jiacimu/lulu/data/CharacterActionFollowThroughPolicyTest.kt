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

    @Test fun identicalUnresolvedWishDoesNotCreateARetryLoop() {
        val choices = listOf("去看看书中那条线索")
        assertFalse(CharacterActionFollowThroughPolicy.shouldReview(true, choices, "same", "same", 299999, 0))
        assertTrue(CharacterActionFollowThroughPolicy.shouldReview(true, choices, "same", "same", 300000, 0))
    }
}
