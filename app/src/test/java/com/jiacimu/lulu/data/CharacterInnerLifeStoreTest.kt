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

    @Test fun preActionExpectationsAndLaterAppraisalUseOneStateAndDeletionRetractsBoth() {
        start()
        val role = "inside-test-expectation-result"
        val context = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.clear(role)
        val now = Instant.parse("2026-10-16T12:00:00Z")
        CharacterInnerLifeStore.beginActivityExpectation(role, "tool-expected", "play_solo_game",
            JSONObject().put("wanted", "超过900分").put("why", "想进步一点"), now)
        CharacterInnerLifeStore.resolveActivityExpectation(role, "tool-expected", "missing-game", true, "得了840分", now)
        assertEquals("awaiting_result", CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("activityExpectations").getJSONObject(0).getString("status"))
        val source = "real-game-for-expectation"
        SharedExperienceTimeline.record(source, role, "独自游戏", "游戏馆", "实际得分840，没达开局目标900分", now,
            triggerExtraction = false, source = "solo-game", evidenceKind = EventEvidenceKind.ToolResult)
        CharacterInnerLifeStore.resolveActivityExpectation(role, "tool-expected", source, true, "得了840分", now)
        assertNull(CharacterInnerLifeStore.snapshot(role).optJSONObject("emotion"))
        CharacterInnerLifeStore.observe(role, source, "实际得分840", JSONObject().put("emotion", JSONObject()
            .put("feeling", "有点不服气，也觉得好笑").put("cause", "没达自己定的900分目标")), now = now)
        assertTrue(CharacterInnerLifeStore.compactContext(role, now).contains("超过900分"))
        SharedExperienceTimeline.deleteEvent(source)
        assertEquals(0, CharacterInnerLifeStore.snapshot(role).getJSONArray("activityExpectations").length())
        assertNull(CharacterInnerLifeStore.snapshot(role).optJSONObject("emotion"))
        CharacterInnerLifeStore.clear(role)
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

    @Test fun endedWishesDoNotExhaustCapacityOrPolluteCurrentDeliberation() {
        start()
        val role = "inside-test-closed-goal-capacity"
        CharacterInnerLifeStore.clear(role)
        for (i in 1..6) {
            CharacterInnerLifeStore.observe(role, "new-chapter-$i", "看到了不同的悬念",
                JSONObject().put("motives", JSONArray().put(JSONObject()
                    .put("op", "start").put("aim", "读完第${i}章节中的故事")
                    .put("why", "想知道人物在第${i}章的选择"))))
            val all = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            val id = all.getJSONObject(i - 1).getString("id")
            CharacterInnerLifeStore.observe(role, "end-chapter-$i", "读完后转移了兴趣",
                JSONObject().put("motives", JSONArray().put(JSONObject()
                    .put("op", "release").put("id", id).put("reason", "不再关注这一章节"))))
        }
        CharacterInnerLifeStore.observe(role, "next-book", "开始阅读另一个故事",
            JSONObject().put("motives", JSONArray().put(JSONObject()
                .put("op", "start").put("aim", "读完新发现的长篇小说")
                .put("why", "故事的新角色引起了兴趣"))))
        val all = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
        assertEquals(7, all.length())
        assertEquals(1, CharacterMotiveState.active(all).size)
        val current = CharacterInnerLifeStore.context(role)
        assertTrue(current.contains("读完新发现的长篇小说"))
        assertFalse(current.contains("读完第1章节中的故事"))
        CharacterInnerLifeStore.clear(role)
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

    @Test fun commonGroundIsEvidenceBoundAndRestoresAfterDeletion() {
        start()
        val id = "inside-test-common-ground"
        CharacterInnerLifeStore.clear(id)
        val first = JSONObject()
            .put("meaning", "她在纠正我刚才的误解")
            .put("responseAim", "先修复误解")
            .put("commonGroundUpdate", "她并不是生气，只是在说明原意")
            .put("uncertainty", "她现在是否还想继续这个话题")
            .put("interactionMove", "repair")
        CharacterInnerLifeStore.recordInteractionAppraisal(id, "direct:user", "msg-ground-1", first)
        var context = CharacterInnerLifeStore.interactionContext(id, "direct:user")
        assertTrue(context.contains("修复"))
        assertTrue(context.contains("她并不是生气"))
        assertTrue(context.contains("她现在是否还想继续这个话题"))

        val second = JSONObject()
            .put("meaning", "她明确说想继续聊")
            .put("commonGroundUpdate", "她想继续这个话题")
            .put("uncertainty", "")
            .put("interactionMove", "answer")
        CharacterInnerLifeStore.recordInteractionAppraisal(id, "direct:user", "msg-ground-2", second)
        context = CharacterInnerLifeStore.interactionContext(id, "direct:user")
        assertFalse(context.contains("仍未确认的点：她现在是否还想继续这个话题"))
        CharacterInnerLifeStore.invalidateEvidence("msg-ground-2")
        context = CharacterInnerLifeStore.interactionContext(id, "direct:user")
        assertTrue(context.contains("她现在是否还想继续这个话题"))
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun groundingCandidateCanBeRejectedAndRestoredWithEvidenceLifecycle() {
        start()
        val id = "inside-test-grounding-events"
        CharacterInnerLifeStore.clear(id)
        CharacterInnerLifeStore.recordGroundingCandidate(
            id, "direct:user", "msg-candidate", "超时=她想罚我", 0.74,
        )
        var context = CharacterInnerLifeStore.groundingContext(id, "direct:user")
        assertTrue(context.contains("候选理解"))
        assertTrue(context.contains("她想罚我"))
        CharacterInnerLifeStore.rejectGroundingCandidates(
            id, "direct:user", "msg-repair", "用户明确说我没理解对",
        )
        context = CharacterInnerLifeStore.groundingContext(id, "direct:user")
        assertTrue(context.contains("已被用户否定"))
        assertFalse(context.contains("候选理解（尚未确认"))
        CharacterInnerLifeStore.invalidateEvidence("msg-repair")
        context = CharacterInnerLifeStore.groundingContext(id, "direct:user")
        assertTrue(context.contains("候选理解"))
        assertFalse(context.contains("已被用户否定"))
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun relationshipUsesMultiTurnTrendsInsteadOfSingleGoodwillScore() {
        start()
        val id = "inside-test-relation-trend"
        CharacterInnerLifeStore.clear(id)
        fun social(reason: String) = JSONObject().put("social", JSONObject()
            .put("targetId", "user")
            .put("interpretation", "她在认真听我说")
            .put("reason", reason)
            .put("dimensions", JSONObject()
                .put("trust", "up").put("warmth", "up").put("ease", "up")
                .put("friction", "down").put("boundarySafety", "up")))
        CharacterInnerLifeStore.observe(id, "rel-1", "用户认真回应了一次", social("第一次实际回应"), setOf("user"))
        var compact = CharacterInnerLifeStore.compactContext(id)
        assertTrue(compact.contains("信任=证据不足"))
        CharacterInnerLifeStore.observe(id, "rel-2", "用户再次认真回应", social("第二次独立回应"), setOf("user"))
        compact = CharacterInnerLifeStore.compactContext(id)
        assertTrue(compact.contains("信任=上升"))
        assertTrue(compact.contains("亲近/温度=上升"))
        assertTrue(compact.contains("未解摩擦=下降"))
        CharacterInnerLifeStore.invalidateEvidence("rel-2")
        compact = CharacterInnerLifeStore.compactContext(id)
        assertTrue(compact.contains("信任=证据不足"))
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun causalTransitionIsEvidenceBoundAndDeletedWithItsSource() {
        start()
        val id = "inside-test-causal-transition"
        CharacterInnerLifeStore.clear(id)
        val appraisal = JSONObject()
            .put("meaning", "她是在纠正我的误解")
            .put("responseAim", "先修复，不抢着解释自己")
            .put("interactionMove", "repair")
        val innerLife = JSONObject().put("emotion", JSONObject()
            .put("feeling", "有点尴尬")
            .put("cause", "意识到自己刚才理解错了"))
        val basis = JSONObject()
            .put("focus", "用户明确说“不是这个意思”")
            .put("change", "从原来的判断改成承认自己理解错了")
            .put("unsaidWhy", "尴尬没有必要直接说给她听")
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = id,
            evidenceId = "causal-msg-1",
            appraisal = appraisal,
            innerLife = innerLife,
            innerThoughtBasis = basis,
            selectedAction = "reply",
            innerThought = "……刚才确实是我想岔了",
            reason = "先把误解修回来",
            now = Instant.parse("2026-10-10T10:30:00Z"),
        )
        val transitions = CharacterInnerLifeStore.snapshot(id).getJSONArray("causalTransitions")
        assertEquals(1, transitions.length())
        val entry = transitions.getJSONObject(0)
        assertEquals("causal-msg-1", entry.getString("evidenceId"))
        assertEquals("repair", entry.getJSONObject("appraisal").getString("interactionMove"))
        assertEquals("有点尴尬", entry.getJSONObject("stateDelta").getJSONObject("emotion").getString("feeling"))
        assertEquals("用户明确说“不是这个意思”", entry.getJSONObject("innerThoughtBasis").getString("focus"))
        CharacterInnerLifeStore.invalidateEvidence("causal-msg-1")
        assertEquals(0, CharacterInnerLifeStore.snapshot(id).getJSONArray("causalTransitions").length())
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun forgedSuccessfulActionCannotMoveAContinuousGoalIntoWaitingFeedback() {
        start()
        val role = "inside-test-proof"
        CharacterInnerLifeStore.clear(role)
        CharacterInnerLifeStore.observe(role, "saw-book", "想继续读一本书",
            JSONObject().put("motives", JSONArray().put(JSONObject()
                .put("op", "start").put("aim", "读完这本书")
                .put("why", "自己好奇书中的人物"))))
        val id = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getString("id")
        CharacterInnerLifeStore.recordActionResult(role, id,
            "fabricated-success", "read_book", true, "我读完了")
        val goal = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        assertEquals("active", goal.getString("status"))
        assertEquals(0, goal.getJSONArray("outcomes").length())
        CharacterInnerLifeStore.recordActionResult(role, id,
            "actual-attempt-failed", "read_book", false, "本次打开书失败")
        assertEquals(1, CharacterInnerLifeStore.snapshot(role).getJSONArray("motives")
            .getJSONObject(0).getJSONArray("outcomes").length())
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

    @Test fun nearDuplicateInnerVoicesWithinTenMinutesDoNotCreateNewMoments() {
        start()
        val id = "inside-test-voice-dedupe"
        CharacterInnerLifeStore.clear(id)
        val now = Instant.parse("2026-10-10T08:00:00Z")
        CharacterInnerLifeStore.recordInnerVoice(id, "online-awareness-1", "还是先等她回消息吧", now)
        CharacterInnerLifeStore.recordInnerVoice(id, "online-awareness-2", "还是先等她回消息", now.plusSeconds(120))
        assertEquals(1, CharacterInnerLifeStore.snapshot(id).getJSONArray("innerVoices").length())
        CharacterInnerLifeStore.recordInnerVoice(id, "online-awareness-3", "突然想起那本书还没看完", now.plusSeconds(180))
        assertEquals(2, CharacterInnerLifeStore.snapshot(id).getJSONArray("innerVoices").length())
        CharacterInnerLifeStore.clear(id)
    }

    @Test fun emotionContextShowsProgrammaticTimeDecay() {
        start()
        val id = "inside-test-emotion-decay"
        CharacterInnerLifeStore.clear(id)
        val now = Instant.parse("2026-10-10T08:00:00Z")
        val proposal = JSONObject().put("emotion", JSONObject()
            .put("feeling", "有点生气").put("cause", "刚刚发生了争执")
            .put("strength", 4).put("halfLifeMinutes", 60))
        CharacterInnerLifeStore.observe(id, "argument-decay", "刚刚发生了争执", proposal, setOf("user"), now)
        val fresh = CharacterInnerLifeStore.compactContext(id, now)
        val later = CharacterInnerLifeStore.compactContext(id, now.plusSeconds(2 * 3600))
        assertTrue(fresh.contains("当前影响=强"))
        assertTrue(later.contains("当前影响=较弱"))
        assertTrue(later.contains("旧事被想起不等于重新受刺激"))
        CharacterInnerLifeStore.clear(id)
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

    @Test fun motiveStepPersistsAcrossRevisionsAndDeletedRevisionLosesItsPlan() {
        start()
        val role = "inside-test-linked-plans"
        CharacterInnerLifeStore.clear(role)
        val at = Instant.parse("2026-10-15T12:00:00Z")
        CharacterInnerLifeStore.observe(role, "plan-evidence-1", "确实对书有兴趣",
            JSONObject().put("motives", JSONArray().put(JSONObject()
                .put("op", "start").put("ref", "read-more")
                .put("aim", "弄懂小说的悬念").put("why", "真的想知道结局")
                .put("nextStep", "接着阅读第三章").put("waitingFor", "等通话结束")
                .put("reviewAfter", "2026-10-16T12:00:00Z"))), now = at)
        val motive = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        assertEquals("接着阅读第三章", motive.getString("nextStep"))
        assertFalse(CharacterMotiveProgress.isDue(motive, at))
        assertEquals(motive.getString("id"), CharacterMotiveProgress.resolveMotiveId(
            CharacterInnerLifeStore.snapshot(role).optJSONArray("motives"),
            "", "read-more", "plan-evidence-1"))
        assertEquals("", CharacterMotiveProgress.resolveMotiveId(
            CharacterInnerLifeStore.snapshot(role).optJSONArray("motives"),
            "", "read-more", "someone-elses-evidence"))
        CharacterInnerLifeStore.observe(role, "plan-evidence-2", "通话结束",
            JSONObject().put("motives", JSONArray().put(JSONObject()
                .put("op", "revise").put("id", motive.getString("id"))
                .put("reason", "现在有空").put("waitingFor", "")
                .put("reviewAfter", "").put("nextStep", "打开第三章"))),
            now = at.plusSeconds(90))
        val revised = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        assertEquals(motive.getString("id"), revised.getString("id"))
        assertEquals("打开第三章", revised.getString("nextStep"))
        assertTrue(CharacterMotiveProgress.isDue(revised, at.plusSeconds(91)))
        CharacterInnerLifeStore.invalidateEvidence("plan-evidence-2")
        val revoked = CharacterInnerLifeStore.snapshot(role).getJSONArray("motives").getJSONObject(0)
        assertFalse(revoked.has("nextStep"))
        CharacterInnerLifeStore.clear(role)
    }

}
