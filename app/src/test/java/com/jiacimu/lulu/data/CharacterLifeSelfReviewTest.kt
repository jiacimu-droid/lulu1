package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.json.JSONObject
import org.json.JSONArray
import kotlinx.coroutines.runBlocking
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

    @Test fun olderHeldThoughtsRotateWithoutAutoRetiringOrPromotingThem() {
        val thoughts = JSONArray()
        val at = Instant.parse("2026-11-01T12:00:00Z")
        repeat(27) { index ->
            thoughts.put(JSONObject()
                .put("thought", "我还在考虑自己的事-$index")
                .put("at", at.minusSeconds((index + 1) * 3_600L).toString())
                .put("status", if (index == 15) "discarded" else "held"))
        }
        val state = JSONObject().put("thoughts", thoughts)
        val first = CharacterLifeSelfReview.thoughtCandidates(
            state, at, ZoneId.of("Asia/Tokyo")).map { it.getString("thought") }
        val next = CharacterLifeSelfReview.thoughtCandidates(
            state, at.plusSeconds(86_400), ZoneId.of("Asia/Tokyo"))
            .map { it.getString("thought") }
        assertTrue(first.size in 4..11)
        assertTrue(next.size in 4..11)
        assertNotEquals(first, next)
        assertFalse(first.any { it.endsWith("-15") })
        assertFalse(next.any { it.endsWith("-15") })
        assertTrue(first.contains("我还在考虑自己的事-26"))
        assertTrue(next.contains("我还在考虑自己的事-26"))
        // Selection does not write a status, erase history or create a task.
        assertEquals(27, state.getJSONArray("thoughts").length())
        assertEquals("held", state.getJSONArray("thoughts").getJSONObject(0).getString("status"))
    }

    @Test fun ownLearnedGrowthCanBeRetiredWithReceiptButNotOwnersFactsOrOtherRoles() = runBlocking<Unit> {
        CharacterDevelopmentStore.initialize(context)
        val global = CharacterDevelopmentStore.javaClass.getDeclaredField("records")
            .apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val before = (global.get(CharacterDevelopmentStore) as List<DevelopmentRecord>).toList()
        val save = CharacterDevelopmentStore.javaClass.getDeclaredMethod(
            "save", List::class.java).apply { isAccessible = true }
        val persona = CharacterRuntime.personaConstraintSnapshot(role)
        val at = Instant.now().minusSeconds(100)
        val learned = DevelopmentRecord(
            id = "growth-review-subjective", characterId = role, slot = "self-taste",
            kind = DevelopmentKind.Interest, content = "以前喜欢在夜里阅读",
            confidence = 0.8, evidence = emptyMap(), counterEvidence = emptyMap(),
            version = 3, createdAt = at, personaSnapshot = persona,
        )
        val ownerFact = learned.copy(id = "growth-review-owner-fact", slot = "owner-fact",
            kind = DevelopmentKind.UserFact, content = "主人明确说过的事情")
        try {
            save.invoke(CharacterDevelopmentStore, before + learned + ownerFact)
            val review = CharacterLifeSelfReview.context(context, role)
            assertTrue(review.contains("growth-review-subjective"))
            assertTrue(review.contains("retire_development_record"))
            assertFalse(CharacterDevelopmentStore.retireSelfAssessment(
                "unrelated-character", learned.id, learned.version))
            assertFalse(CharacterDevelopmentStore.retireSelfAssessment(
                role, learned.id, learned.version - 1))
            assertFalse(CharacterDevelopmentStore.retireSelfAssessment(
                role, ownerFact.id, ownerFact.version))
            val requestId = "self-review-retire-${java.util.UUID.randomUUID()}"
            val result = JSONObject(ToolRouter.execute(
                context, role, "retire_development_record",
                JSONObject().put("developmentId", learned.id)
                    .put("version", learned.version)
                    .put("reason", "真实新经历让我不再认同这条旧倾向"),
                requestId = requestId,
            ))
            assertTrue(result.optString("summary"), result.optBoolean("success"))
            assertFalse(CharacterDevelopmentStore.active(role).any { it.id == learned.id })
            assertTrue(CharacterDevelopmentStore.active(role).any { it.id == ownerFact.id })
            assertTrue(CharacterDevelopmentStore.history(role).any {
                it.id == learned.id && !it.active
            })
            assertTrue(SharedExperienceTimeline.eventsByIds(role,
                listOf("tool-$role-$requestId")).isNotEmpty())
        } finally {
            save.invoke(CharacterDevelopmentStore, before)
        }
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
