package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.ZoneId

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterLifeSelfReviewTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val role = "role-owned-heart-review-test"

    @Before fun setup() {
        MemoryTestEnvironment.release()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterLearningAgenda.initialize(context)
        CharacterCuriosityRuntime.initialize(context)
        CharacterCuriosityRuntime.clear(role)
        CharacterInnerLifeStore.clear(role)
    }

    @After fun cleanup() {
        CharacterCuriosityRuntime.clear(role)
        CharacterInnerLifeStore.clear(role)
        MemoryTestEnvironment.release()
    }

    @Test fun dailyOpportunityUsesCharactersLocalDateAndNeverRequiresAnotherModel() {
        val zone = ZoneId.of("Asia/Tokyo")
        val now = Instant.parse("2026-10-25T16:30:00Z")
        assertTrue(CharacterLifeSelfReview.isDue("2026-10-25", now, zone))
        assertFalse(CharacterLifeSelfReview.isDue("2026-10-26", now, zone))
        assertEquals("2026-10-26", CharacterLifeSelfReview.localDay(now, zone))
    }

    @Test fun roleCanSeeAllSavedQuestionsAndChooseSeveralSchedulesInOneDecision() {
        val at = Instant.now().minusSeconds(60)
        SharedExperienceTimeline.record("heart-review-source", role, "私聊", "用户",
            "我们谈过几个未完成的问题", at, triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        val questions = listOf(
            "活动玩法" to "下一次活动有哪些玩法",
            "生活安排" to "怎样更好地规划自己的空闲时间",
            "阅读计划" to "想读的资料应该先读哪篇",
        )
        questions.forEach { (topic, question) ->
            assertTrue(CharacterCuriosityRuntime.recordInquiry(role,
                JSONObject().put("topic", topic).put("question", question)
                    .put("why", "真实聊天中想到了这个问题"),
                "heart-review-source", "曾经真实聊过", at))
        }
        val content = CharacterLifeSelfReview.context(context, role, at.plusSeconds(3))
        questions.forEach { (topic, question) ->
            assertTrue("遗漏主题：$topic", content.contains(topic))
            assertTrue("遗漏问题：$question", content.contains(question))
        }
        assertTrue(content.contains("plan_personal_activity"))
        assertTrue(content.contains("reschedule_personal_activity"))
        assertTrue(content.contains("cancel_personal_activity"))
        assertTrue(content.contains("同一轮安排几件不同的事"))
        assertEquals(3, CharacterCuriosityRuntime.visibleQuestions(role).size)
        // Preparing a review must not silently retire, start or complete anything.
        assertEquals(0, RoleDayScheduleStore.plans(context, role).size)
        assertTrue(SharedExperienceTimeline.eventsByIds(role,
            listOf("heart-review-source")).isNotEmpty())
        SharedExperienceTimeline.deleteEvent("heart-review-source")
    }
}
