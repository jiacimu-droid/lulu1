package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterInnerLifeStoreTest {
    private fun start() {
        CharacterInnerLifeStore.initialize(RuntimeEnvironment.getApplication() as Context)
        CharacterInnerLifeStore.clear("inside-test-a")
        CharacterInnerLifeStore.clear("inside-test-b")
    }

    @Test fun strongRecordedRegretSurvivesFiveMinutePresenceWindowForAftercare() {
        start()
        val id = "inside-test-aftercare"
        CharacterInnerLifeStore.clear(id)
        val spokenAt = Instant.parse("2026-10-09T11:00:00Z")
        val proposal = JSONObject().put("emotion", JSONObject()
            .put("feeling", "对刚才的争执很歉疚")
            .put("cause", "确实失约，刚刚向用户道歉")
            .put("strength", 3)
            .put("halfLifeMinutes", 360))
        CharacterInnerLifeStore.observe(id, "sent-reply-1", "已发送的道歉", proposal, setOf("user"), spokenAt)
        assertTrue(CharacterInnerLifeStore.needsPostOnlineReflection(id, spokenAt.plusSeconds(45 * 60)))
        assertFalse(CharacterInnerLifeStore.needsPostOnlineReflection(id, spokenAt.plusSeconds(61 * 60)))
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun groupThoughtsAreRemovedWhenTheirRealMessageIsDeleted() {
        start()
        val characterId = "inside-test-group-thoughts"
        CharacterInnerLifeStore.clear(characterId)
        CharacterInnerLifeStore.observe(characterId, "message-123:group:$characterId",
            "群成员在真实群聊中发生分歧", JSONObject().put("thoughts", JSONArray()
                .put(JSONObject().put("thought", "我不太赞同，但也想听完")
                    .put("impulse", "回应分歧").put("hesitation", "先听其他人说"))))
        assertEquals(1, CharacterInnerLifeStore.snapshot(characterId).getJSONArray("thoughts").length())
        CharacterInnerLifeStore.invalidateEvidence("message-123:group:$characterId")
        assertEquals(0, CharacterInnerLifeStore.snapshot(characterId).getJSONArray("thoughts").length())
        CharacterInnerLifeStore.clear(characterId)
    }

    @Test fun conflictingThoughtsAreRememberedWithoutPretendingTheyAreActions() {
        start()
        val id = "inside-test-conflicting-thoughts"
        CharacterInnerLifeStore.clear(id)
        val moment = Instant.parse("2026-10-09T11:00:00Z")
        val proposal = JSONObject().put("thoughts", JSONArray()
            .put(JSONObject().put("thought", "我很想主动弥补这次争执")
                .put("impulse", "给她解释和道歉").put("hesitation", "不想逼她马上回应"))
            .put(JSONObject().put("thought", "可是我自己也有点委屈")
                .put("impulse", "暂时独处").put("hesitation", "担心她误会我不在乎")))
        CharacterInnerLifeStore.observe(id, "real-argument", "双方确实发生了争执", proposal,
            setOf("user"), moment)
        val thoughts = CharacterInnerLifeStore.snapshot(id).getJSONArray("thoughts")
        assertEquals(2, thoughts.length())
        assertTrue(CharacterInnerLifeStore.context(id, moment.plusSeconds(20))
            .contains("我很想主动弥补这次争执"))
        assertTrue(CharacterInnerLifeStore.context(id, moment.plusSeconds(20))
            .contains("可是我自己也有点委屈"))
        assertFalse(CharacterInnerLifeStore.context(id, moment.plusSeconds(25 * 3600))
            .contains("我很想主动弥补这次争执"))
        assertNull(CharacterInnerLifeStore.snapshot(id).optJSONArray("decisions"))
        CharacterInnerLifeStore.invalidateEvidence("real-argument")
        assertEquals(0, CharacterInnerLifeStore.snapshot(id).getJSONArray("thoughts").length())
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun distinctWishesPersistAndOneCanBePausedWithoutDeletingOthers() {
        start()
        val proposals = JSONObject().put("motives", JSONArray()
            .put(JSONObject().put("op", "start").put("aim", "继续读小说").put("why", "想知道结局"))
            .put(JSONObject().put("op", "start").put("aim", "改天问候用户").put("why", "今天听她说累了")))
        val moment = Instant.parse("2026-10-09T11:00:00Z")
        CharacterInnerLifeStore.observe("inside-test-a", "user-msg-1", "用户谈起新书", proposals, setOf("user"), moment)
        assertEquals(2, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives").length())
        // Same event may be delivered more than once, but must not duplicate feelings or goals.
        CharacterInnerLifeStore.observe("inside-test-a", "user-msg-1", "用户谈起新书", proposals, setOf("user"), moment)
        assertEquals(2, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives").length())

        val id = CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives")
            .getJSONObject(0).getString("id")
        CharacterInnerLifeStore.observe("inside-test-a", "user-msg-2", "今天想休息", JSONObject().put("motives",
            JSONArray().put(JSONObject().put("op", "pause").put("id", id).put("reason", "当下想先休息"))),
            setOf("user"), moment.plusSeconds(90))
        assertEquals("paused", CharacterInnerLifeStore.snapshot("inside-test-a")
            .getJSONArray("motives").getJSONObject(0).getString("status"))
        assertEquals(2, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives").length())
        CharacterInnerLifeStore.stopMotive("inside-test-a", id)
        assertEquals(1, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives").length())
    }

    @Test fun relationshipRequiresWitnessAndNeverLeaksBetweenCharacters() {
        start()
        val sentiment = JSONObject().put("social", JSONObject()
            .put("targetId", "friend-b").put("interpretation", "看起来嘴硬但肯帮忙")
            .put("reason", "在共同群聊主动接了话"))
        CharacterInnerLifeStore.observe("inside-test-a", "private-1", "用户：在吗", sentiment, setOf("user"))
        assertNull(CharacterInnerLifeStore.snapshot("inside-test-a").optJSONObject("bonds"))
        CharacterInnerLifeStore.observe("inside-test-a", "group-msg-2", "朋友确实在群里主动接了话",
            sentiment, setOf("friend-b", "user"))
        assertEquals("看起来嘴硬但肯帮忙", CharacterInnerLifeStore.snapshot("inside-test-a")
            .getJSONObject("bonds").getJSONObject("friend-b").getString("interpretation"))
        assertNull(CharacterInnerLifeStore.snapshot("inside-test-b").optJSONObject("bonds"))
    }

    @Test fun actionProofNeedsMatchingMotiveIdAndReceiptIsIdempotent() {
        start()
        CharacterInnerLifeStore.observe("inside-test-a", "msg", "他很想继续看书",
            JSONObject().put("motives", JSONArray().put(JSONObject()
                .put("op", "start").put("aim", "读完故事").put("why", "喜欢里面的人物"))))
        val id = CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives").getJSONObject(0).getString("id")
        CharacterInnerLifeStore.recordActionResult("inside-test-a", "missing-id", "receipt-1", "read_book", true, "读了一章")
        CharacterInnerLifeStore.recordActionResult("inside-test-a", id, "receipt-1", "read_book", false, "书本打不开")
        CharacterInnerLifeStore.recordActionResult("inside-test-a", id, "receipt-1", "read_book", true, "假的成功")
        val outcomes = CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("motives")
            .getJSONObject(0).getJSONArray("outcomes")
        assertEquals(1, outcomes.length())
        assertFalse(outcomes.getJSONObject(0).getBoolean("success"))
        assertTrue(CharacterInnerLifeStore.context("inside-test-a").contains("书本打不开"))
    }

    @Test fun layeredFeelingsRememberTheirCausesAndDoNotDuplicateReplays() {
        start()
        val now = Instant.parse("2026-10-09T08:00:00Z")
        fun feeling(label: String, cause: String, impulse: String) = JSONObject().put(
            "emotion", JSONObject().put("feeling", label).put("cause", cause)
                .put("impulse", impulse).put("restraint", "不想马上表露")
                .put("otherFeeling", "还有一点犹豫"),
        )
        CharacterInnerLifeStore.observe("inside-test-a", "msg-a", "她夸了他",
            feeling("心里一热", "听到夸奖", "想多听一次"), setOf("user"), now)
        CharacterInnerLifeStore.observe("inside-test-a", "msg-b", "她转移了话题",
            feeling("略微失落", "话题突然变了", "想追问"), setOf("user"), now.plusSeconds(50))
        val root = CharacterInnerLifeStore.snapshot("inside-test-a")
        assertEquals("略微失落", root.getJSONObject("emotion").getString("feeling"))
        assertEquals(1, root.getJSONArray("emotionHistory").length())
        assertTrue(CharacterInnerLifeStore.context("inside-test-a", now.plusSeconds(60)).contains("心里一热"))
        val compact = CharacterInnerLifeStore.compactContext("inside-test-a", now.plusSeconds(60))
        assertTrue(compact.contains("心里一热"))
        assertTrue(compact.contains("略微失落"))
        assertTrue(compact.indexOf("略微失落") < compact.indexOf("此前仍可能有余波"))
        CharacterInnerLifeStore.observe("inside-test-a", "msg-b", "她转移了话题",
            feeling("略微失落", "话题突然变了", "想追问"), setOf("user"), now.plusSeconds(50))
        assertEquals(1, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("emotionHistory").length())
        CharacterInnerLifeStore.invalidateEvidence("msg-a")
        assertEquals(0, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("emotionHistory").length())
    }

    @Test fun factualAfterglowBecomesEmotionWithoutExtraModelCall() {
        start()
        val glow = JSONObject().put("feeling", "突然高兴起来")
            .put("impulse", "想再说一句").put("holdHours", 2)
        val proposed = CharacterInnerLifeStore.withAfterglow(null, glow, "用户真实发来的问候")
        assertEquals("突然高兴起来", proposed!!.getJSONObject("emotion").getString("feeling"))
        assertEquals("用户真实发来的问候", proposed.getJSONObject("emotion").getString("cause"))
        assertNull(CharacterInnerLifeStore.withAfterglow(null, glow, ""))
    }

    @Test fun severeRecentEmotionQualifiesForAftercareButOldEmotionDoesNot() {
        start()
        val now = Instant.parse("2026-10-09T09:00:00Z")
        val serious = JSONObject().put("emotion", JSONObject()
            .put("feeling", "深深后悔").put("cause", "做过的事让对方伤心")
            .put("strength", 4))
        CharacterInnerLifeStore.observe("inside-test-a", "argument-1",
            "双方真的发生过争执", serious, setOf("user"), now)
        assertTrue(CharacterInnerLifeStore.needsPostOnlineReflection("inside-test-a", now.plusSeconds(300)))
        assertTrue(CharacterInnerLifeStore.needsPostOnlineReflection("inside-test-a", now.plusSeconds(1300)))
        assertTrue(CharacterInnerLifeStore.needsPostOnlineReflection("inside-test-a", now.plusSeconds(3600)))
        assertFalse(CharacterInnerLifeStore.needsPostOnlineReflection("inside-test-a", now.plusSeconds(3660)))
        assertFalse(CharacterInnerLifeStore.needsPostOnlineReflection("inside-test-b", now.plusSeconds(300)))
    }

    @Test fun unsaidInnerVoiceIsContinuousButDeletedWithSource() {
        start()
        CharacterInnerLifeStore.recordInnerVoice("inside-test-a", "event-true-1", "……好想多问一句")
        CharacterInnerLifeStore.recordInnerVoice("inside-test-a", "event-true-1", "重复收到了旧回复")
        CharacterInnerLifeStore.recordInnerVoice("inside-test-a", "event-true-2", "算了，先听她说完")
        assertEquals(2, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("innerVoices").length())
        assertTrue(CharacterInnerLifeStore.context("inside-test-a").contains("先听她说完"))
        CharacterInnerLifeStore.invalidateEvidence("event-true-1")
        val voices = CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("innerVoices")
        assertEquals(1, voices.length())
        assertFalse(CharacterInnerLifeStore.context("inside-test-a").contains("好想多问一句"))
    }

    @Test fun emotionsCorrectionsAndVoiceSamplesNeedAnchors() {
        start()
        val emotion = JSONObject().put("emotion", JSONObject().put("feeling", "突然很开心")
            .put("cause", "用户叫了我的小名").put("halfLifeMinutes", 30))
        val now = Instant.parse("2026-10-09T11:00:00Z")
        CharacterInnerLifeStore.observe("inside-test-a", "", "", emotion, setOf("user"), now)
        assertNull(CharacterInnerLifeStore.snapshot("inside-test-a").optJSONObject("emotion"))
        CharacterInnerLifeStore.observe("inside-test-a", "real-msg", "用户叫了我的小名", emotion, setOf("user"), now)
        assertTrue(CharacterInnerLifeStore.context("inside-test-a", now.plusSeconds(60)).contains("突然很开心"))
        assertFalse(CharacterInnerLifeStore.context("inside-test-a", now.plusSeconds(6000)).contains("突然很开心"))
        CharacterInnerLifeStore.recordSpokenText("inside-test-a", "sent-1", "……你再说一遍？")
        CharacterInnerLifeStore.recordSpokenText("inside-test-a", "sent-1", "这句是重复推送")
        assertEquals(1, CharacterInnerLifeStore.snapshot("inside-test-a").getJSONArray("voice").length())
        CharacterInnerLifeStore.clear("inside-test-a")
        assertEquals(0, CharacterInnerLifeStore.snapshot("inside-test-a").length())
    }
    @Test fun reflectionOnSameSourceDoesNotRestartEmotionClockAndCombinedDeletionClearsEmotion() {
        start()
        val now = Instant.parse("2026-10-10T06:00:00Z")
        val proposal = JSONObject().put("emotion", JSONObject().put("feeling", "还是很在意")
            .put("cause", "这次共同经历").put("halfLifeMinutes", 30))
        val source = "perception-sources:chat-source:world-touch-source:"
        CharacterInnerLifeStore.observe("inside-test-a", source, "最初理解", proposal, setOf("user"), now)
        CharacterInnerLifeStore.observe("inside-test-a", source, "再次理解同一事实", proposal, setOf("user"), now.plusSeconds(1800))
        assertEquals(now.toString(), CharacterInnerLifeStore.snapshot("inside-test-a")
            .getJSONObject("emotion").getString("startedAt"))
        assertFalse(CharacterInnerLifeStore.compactContext("inside-test-a", now.plusSeconds(6000)).contains("还是很在意"))
        CharacterInnerLifeStore.invalidateEvidence("world-touch-source")
        assertNull(CharacterInnerLifeStore.snapshot("inside-test-a").optJSONObject("emotion"))
    }
}
