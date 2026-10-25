package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterLifeVisibilityTest {
    private val ctx get() = RuntimeEnvironment.getApplication() as Context

    @Before fun init() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        CharacterInnerLifeStore.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        CharacterCuriosityRuntime.initialize(ctx)
        CharacterLifeFollowThroughStore.initialize(ctx)
        CharacterDevelopmentStore.initialize(ctx)
    }

    @After fun close() {
        MemoryTestEnvironment.release()
    }

    @Test fun schedulesShowFutureAndOverdueSlotsWithoutInventingCompletionOrMixingOwners() {
        val now = Instant.parse("2026-10-25T02:00:00Z")
        val future = RoleDayPlan("future", "schedule-owner", "继续探索故事伏笔",
            "想核对真实原文", now.plusSeconds(3600), now.minusSeconds(60),
            activityAction = "learn_topic", activityArgs = "{\"goalId\":\"original-goal\"}")
        val overdue = future.copy(id = "overdue", dueAt = now.minusSeconds(30), status = "due")
        val plans = listOf(future, overdue, future.copy(characterId = "other-owner"),
            future.copy(id = "completed", status = "completed"),
            future.copy(id = "cancelled", status = "cancelled"),
            future.copy(id = "not-yet-created", createdAt = now.plusSeconds(1)))
        val shown = CharacterLifeVisibility.scheduledMatters("schedule-owner", plans, now)
        assertEquals(listOf("own-plan-overdue", "own-plan-future"), shown.map { it.evidenceId })
        assertTrue(shown.all { it.category == "日程" })
        assertTrue(shown[0].detail.contains("到期待处理"))
        assertTrue(shown[1].detail.contains("已安排"))
        assertFalse(shown.any { it.detail.contains("已完成") })
        assertTrue(CharacterLifeVisibility.scheduledMatters("unrelated", plans, now).isEmpty())
        assertTrue(CharacterLifeVisibility.scheduledMatters("schedule-owner",
            listOf(future.copy(status = "cancelled")), now).isEmpty())
    }

    @Test fun activeLongTermConcernPersistsInPresenceAndDisappearsWhenPaused() {
        val role = "test-current-concern-long-term"
        val firstSource = "test-long-term-concern-first"
        val pauseSource = "test-long-term-concern-pause"
        val resumeSource = "test-long-term-concern-resume"
        val settledSource = "test-long-term-concern-settled"
        val old = Instant.now().minusSeconds(7L * 86400)
        val now = Instant.now()
        CharacterInnerLifeStore.clear(role)
        listOf(firstSource, pauseSource, resumeSource, settledSource)
            .forEachIndexed { i, id ->
                SharedExperienceTimeline.record(id, role, "私聊", "用户",
                    "与挂心相关的真实第${i + 1}次交流", old.plusSeconds(i * 60L),
                    triggerExtraction = false, source = "message",
                    evidenceKind = EventEvidenceKind.UserStatement)
            }
        val open = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "open").put("topicKey", "contact_cost")
            .put("focus", "担心联系成本让她有负担")
            .put("whyItMatters", "此前了解到她会精打细算"))
        CharacterInnerLifeStore.recordCausalTransition(role, firstSource, open, null, null,
            "reply", "", "当时自己仍在意", old)
        val id = CharacterInnerLifeStore.snapshot(role).getJSONArray("openConcerns")
            .getJSONObject(0).getString("id")
        fun current() = CharacterLifeVisibility.current(role, now).filter { it.title == "还在挂心" }
        fun matters() = CharacterLifeVisibility.matters(role, now).filter { it.category == "挂心" }
        assertEquals(1, current().size)
        assertEquals(1, matters().size)
        assertTrue(current()[0].detail.contains("联系成本"))
        val pause = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "pause").put("threadId", id))
        CharacterInnerLifeStore.recordCausalTransition(role, pauseSource, pause, null, null,
            "reply", "", "暂时不继续关注", now.minusSeconds(90))
        assertTrue(current().isEmpty())
        assertTrue(matters().isEmpty())
        val resume = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "resume").put("threadId", id)
            .put("focus", "想在合适的时机联系对方"))
        CharacterInnerLifeStore.recordCausalTransition(role, resumeSource, resume, null, null,
            "reply", "", "自己再次关心", now.minusSeconds(60))
        assertEquals(1, current().size)
        assertEquals(1, matters().size)
        val settled = JSONObject().put("pendingConcern", JSONObject()
            .put("status", "settled").put("threadId", id)
            .put("resolution", "新的事实使顾虑得到解答"))
        CharacterInnerLifeStore.recordCausalTransition(role, settledSource, settled, null, null,
            "reply", "", "本人已想通", now.minusSeconds(30))
        assertTrue(current().isEmpty())
        assertTrue(matters().isEmpty())
        assertEquals(1, CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("closedConcerns").length())
        listOf(firstSource, pauseSource, resumeSource, settledSource)
            .forEach(SharedExperienceTimeline::deleteEvent)
        CharacterInnerLifeStore.clear(role)
    }

    @Test fun aRealQuestionIsVisibleAsPendingAndWithdrawnWhenSourceDeleted() {
        val role = "visibility-question-source-test"
        val original = "visibility-user-question-source"
        CharacterCuriosityRuntime.clear(role)
        val time = Instant.now()
        SharedExperienceTimeline.record(original, role, "私聊", "用户",
            "我好奇这段故事会怎样发展", time,
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val proposal = JSONObject().put("topic", "故事走向")
            .put("question", "故事后面会怎么发展")
            .put("why", "听到用户说起它")
            .put("nextStep", "下次实际阅读正文")
        CharacterCuriosityRuntime.recordInquiry(role, proposal, original,
            "用户提到了故事", time)
        assertTrue(CharacterLifeVisibility.current(role, time.plusSeconds(2))
            .any { it.title.contains("尚未实践的好奇") })
        SharedExperienceTimeline.deleteEvent(original)
        assertFalse(CharacterLifeVisibility.current(role, time.plusSeconds(3))
            .any { it.title.contains("尚未实践的好奇") })
        CharacterCuriosityRuntime.clear(role)
    }


    @Test fun innerMattersProjectRealSourceWithoutPromotingWishToAction() {
        val role = "life-matters-projection-test"
        val another = "life-matters-other-role"
        val origin = "life-matters-real-origin"
        val at = Instant.now()
        CharacterInnerLifeStore.clear(role)
        CharacterCuriosityRuntime.clear(role)
        SharedExperienceTimeline.record(origin, role, "私聊", "用户",
            "我们聊聊故事的下一章", at, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        CharacterInnerLifeStore.observe(role, origin, "聊了故事",
            JSONObject().put("thoughts", org.json.JSONArray().put(
                JSONObject().put("thought", "这个故事的转折挺有意思")))
                .put("motives", org.json.JSONArray().put(
                    JSONObject().put("op", "start")
                        .put("aim", "找时间把这本故事读到结尾")
                        .put("why", "很想知道这条人物线会如何发展"))),
            now = at)
        CharacterInnerLifeStore.recordInnerVoice(role, origin,
            "也许下一章会推翻现在的猜测", now = at)
        CharacterCuriosityRuntime.recordInquiry(role,
            JSONObject().put("topic", "故事伏笔").put("question", "作者为什么这样安排伏笔")
                .put("why", "读到新的线索").put("nextStep", "有机会再去阅读"),
            origin, "讨论故事", at)
        val visible = CharacterLifeVisibility.matters(role, at.plusSeconds(2))
        assertTrue(visible.any { it.category == "持续意愿" && it.title.contains("故事") })
        assertTrue(visible.any { it.category == "正在琢磨" && it.title.contains("伏笔") })
        assertFalse(visible.any { it.category == "心声" || it.category == "心路历程" })
        assertTrue(visible.all { it.evidenceId == origin })
        assertTrue(CharacterLifeVisibility.matters(another, at.plusSeconds(2)).isEmpty())
        // Recording a wish/question does not create a real tool receipt.
        assertTrue(visible.none { it.detail.contains("上次实际结果") })
        SharedExperienceTimeline.deleteEvent(origin)
        assertTrue(CharacterLifeVisibility.matters(role, at.plusSeconds(3)).isEmpty())
        CharacterInnerLifeStore.clear(role)
        CharacterCuriosityRuntime.clear(role)
    }

    @Test fun readingStaysInOriginalTimelineWithoutInventingAStateMoment() {
        val role = "reading-visibility-proof-test"
        val sourceId = "actual-reading-source-test"
        CharacterInnerLifeStore.clear(role)
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(sourceId)).isEmpty())
        SharedExperienceTimeline.record(sourceId, role, "独自阅读《测试书》", "角色",
            "阅读《测试书》字符 0—300/900，下次从300继续\n阅读感想：这段开头有点意思。",
            Instant.now(), triggerExtraction = false, source = "reading:test-book",
            evidenceKind = EventEvidenceKind.Observation)
        val entry = SharedExperienceTimeline.eventsByIds(role, listOf(sourceId)).single()
        assertTrue(CharacterRecordProjection.eventText(entry).contains("这段开头有点意思"))
        assertTrue(CharacterStateHistory.entries(role, emptyList(), Instant.now().plusSeconds(1)).isEmpty())
        SharedExperienceTimeline.deleteEvent(sourceId)
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(sourceId)).isEmpty())
    }

    @Test fun unverifiedSharingIsNotReportedAsAnActualPublishedPost() {
        val role = "visibility-share-proof-test"
        val origin = "visibility-reading-experience"
        val time = Instant.now()
        CharacterLifeFollowThroughStore.invalidateEvidence(origin)
        SharedExperienceTimeline.record(origin, role, "独自阅读", "角色",
            "读过一本真实存在的书", time, triggerExtraction = false,
            source = "reading:sample", evidenceKind = EventEvidenceKind.Observation)
        CharacterLifeFollowThroughStore.register(role, origin, "独自阅读",
            "读过一本真实存在的书", time)
        CharacterLifeFollowThroughStore.recordShareWish(role,
            JSONObject().put("experienceId", origin)
                .put("status", "held").put("why", "想分享一个有趣的结尾"))
        fun followThrough() = CharacterLifeFollowThroughStore.auditSnapshot(role)
            .single { it.optString("id") == origin }
        assertEquals("held", followThrough().optString("shareStatus"))
        CharacterLifeFollowThroughStore.recordAction(role, origin, "moment", true,
            "说自己发了朋友圈", "not-an-actual-post")
        assertEquals("held", followThrough().optString("shareStatus"))
        assertEquals("", followThrough().optString("shareReceiptId"))
        val post = "visibility-genuine-post"
        SharedExperienceTimeline.record(post, role, "朋友圈", "角色",
            "看到有趣的故事了", time.plusSeconds(1), triggerExtraction = false,
            source = "moment:self", evidenceKind = EventEvidenceKind.CharacterStatement)
        CharacterLifeFollowThroughStore.recordAction(role, origin, "moment", true,
            "朋友圈已发布", post)
        assertEquals("shared", followThrough().optString("shareStatus"))
        assertEquals(post, followThrough().optString("shareReceiptId"))
        SharedExperienceTimeline.deleteEvent(post)
        assertEquals("held", followThrough().optString("shareStatus"))
        assertEquals("", followThrough().optString("shareReceiptId"))
        SharedExperienceTimeline.deleteEvent(origin)
        assertTrue(CharacterLifeFollowThroughStore.auditSnapshot(role).isEmpty())
    }
}
