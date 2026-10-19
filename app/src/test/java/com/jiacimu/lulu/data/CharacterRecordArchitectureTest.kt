package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterRecordArchitectureTest {
    private val role = "record-architecture-role"
    private val now = Instant.parse("2026-10-19T06:00:00Z")

    @Before fun start() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterInnerLifeStore.clear(role)
        CharacterDevelopmentStore.initialize(context)
        CharacterDevelopmentStore.clearCharacter(role)
        CharacterLearningLedger.clearCharacter(role)
        LuluAiServices.initialize(context)
    }

    @After fun finish() {
        CharacterDevelopmentStore.clearCharacter(role)
        CharacterInnerLifeStore.clear(role)
        MemoryTestEnvironment.release()
    }

    private fun reading(id: String, seconds: Long = 0) {
        SharedExperienceTimeline.record(id, role, "独自阅读《故事》", "角色", "实际读到这一段材料",
            now.plusSeconds(seconds), triggerExtraction = false,
            source = "reading:story", evidenceKind = EventEvidenceKind.Observation)
    }
    private fun insight(text: String) = JSONObject().put("topic", "表达方式").put("understanding", text)

    @Test fun oldLinksHaveOneOwnerAndTargetedExpressionsKeepTheirAnchors() {
        assertEquals(CharacterRecordDestination.Memory, CharacterRecordDestination.fromLegacy("Concern"))
        assertEquals(CharacterRecordDestination.Timeline, CharacterRecordDestination.fromLegacy("历程"))
        assertEquals(CharacterRecordDestination.Schedule, CharacterRecordDestination.fromLegacy("Promise"))
        assertEquals(CharacterRecordDestination.Diary, CharacterRecordDestination.fromLegacy(null, "日记标题"))
        assertEquals(CharacterRecordDestination.Favorites, CharacterRecordDestination.fromLegacy(null, favoriteId = "favorite-id"))
        assertEquals(CharacterRecordDestination.Stories, CharacterRecordDestination.fromLegacy("life-story"))
        assertEquals(CharacterRecordDestination.LifeRecords, CharacterRecordDestination.fromLegacy(null))
    }

    @Test fun memoryViewAndActualRecallUseSameRevisedUnderstandingAndRetractSources() = runBlocking {
        reading("architecture-first", 1)
        reading("architecture-revised", 2)
        assertTrue(CharacterLearningLedger.recordReading(role, "architecture-first", insight("最初觉得应该解释所有细节")))
        assertTrue(CharacterLearningLedger.recordReading(role, "architecture-revised",
            insight("后来理解需要给对话留出余地").put("revisesEvidenceId", "architecture-first")))
        val shown = CharacterLearningLedger.listInsights(role)
        assertEquals(listOf("architecture-revised"), shown.map { it.sourceEvidenceId })
        val recalled = UnifiedMemoryOrchestrator.assemble(role, UnifiedMemoryRequest(currentInput = "表达方式"), localOnly = true)
        assertTrue(recalled.understandingContext.contains("给对话留出余地"))
        assertFalse(recalled.understandingContext.contains("应该解释所有细节"))
        assertTrue(recalled.compactPromptSection().contains("给对话留出余地"))
        assertTrue(CharacterRecordProjection.remembered("another-role", emptyList(), shown, emptyList()).isEmpty())
        SharedExperienceTimeline.deleteEvent("architecture-revised")
        assertEquals(listOf("architecture-first"), CharacterLearningLedger.listInsights(role).map { it.sourceEvidenceId })
        SharedExperienceTimeline.deleteEvent("architecture-first")
        assertTrue(CharacterLearningLedger.listInsights(role).isEmpty())
        assertEquals("", UnifiedMemoryOrchestrator.assemble(role, UnifiedMemoryRequest(currentInput = "表达方式"),
            localOnly = true).understandingContext)
    }

    @Test fun aNativeReadingAndItsToolReceiptCannotManufactureTwoGrowthExperiences() {
        reading("one-reading")
        SharedExperienceTimeline.record("receipt-for-one", role, "工具执行", "执行器",
            JSONObject().put("status", "succeeded").put("tool", "read_book")
                .put("result", JSONObject().put("success", true).put("evidenceId", "one-reading").toString()).toString(),
            now, triggerExtraction = false, source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        val persona = CharacterRuntime.personaConstraintSnapshot(role)
        assertFalse(CharacterDevelopmentStore.applyProposal(role, "interest-reading", DevelopmentKind.Interest,
            "开始在意小说里不同的表达方式", listOf("one-reading", "receipt-for-one"), emptyList(), persona))
        reading("second-reading", 30)
        assertTrue(CharacterDevelopmentStore.applyProposal(role, "interest-reading", DevelopmentKind.Interest,
            "开始在意小说里不同的表达方式", listOf("one-reading", "second-reading"), emptyList(), persona))
        assertEquals(1, CharacterRecordProjection.influences(role, listOf("second-reading")).size)
        assertTrue(CharacterRuntime.personalityRuntimeContext(role).contains("不同的表达方式"))
        SharedExperienceTimeline.deleteEvent("second-reading")
        assertTrue(CharacterRecordProjection.influences(role, listOf("one-reading")).isEmpty())
        assertFalse(CharacterRuntime.personalityRuntimeContext(role).contains("不同的表达方式"))
    }

    @Test fun stateAndDecisionAreOneHistoryMomentAndDeletingItKeepsOriginalChat() {
        SharedExperienceTimeline.record("history-user", role, "私聊", "用户", "我先学一会儿", now,
            triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        CharacterInnerLifeStore.recordDecision(role, "history-decision", "silent", "她刚开始专心学习",
            "", null, "没有发送消息", true, now = now, causalEvidenceId = "history-user")
        val innerLife = JSONObject().put("thoughts", JSONArray()
            .put(JSONObject().put("thought", "先安静地陪着"))
            .put(JSONObject().put("thought", "和她一起保持专注")))
        CharacterInnerLifeStore.observe(role, "history-user", "她刚开始专心学习", innerLife, now = now)
        CharacterInnerLifeStore.recordCausalTransition(role, "history-user",
            appraisal = JSONObject().put("tension", "想陪她，也想让她专心"),
            innerLife = innerLife,
            innerThoughtBasis = null, selectedAction = "silent", innerThought = "先安静地陪着",
            reason = "她刚开始专心学习", now = now)
        val state = CompanionPresenceState(role, innerThought = "先安静地陪着", mood = "安定",
            updatedAt = now, provenanceId = "history-user")
        val rows = CharacterStateHistory.entries(role, listOf(state), now.plusSeconds(1))
        assertEquals(1, rows.size)
        assertEquals(1, rows.single().decisions.size)
        assertEquals("暂时保持安静", rows.single().decisions.single().action)
        CharacterStateHistory.delete(role, rows.single())
        assertTrue(CharacterRecordProjection.decisions(role, now.plusSeconds(1)).isEmpty())
        assertEquals(0, CharacterInnerLifeStore.snapshot(role).optJSONArray("causalTransitions")?.length() ?: 0)
        assertEquals(0, CharacterInnerLifeStore.snapshot(role).optJSONArray("thoughts")?.length() ?: 0)
        assertTrue(CharacterStateHistory.entries(role, emptyList(), now.plusSeconds(1)).isEmpty())
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role, listOf("history-user")).size)
    }

    @Test fun historicalMessageCannotShowAConcernRevisedAfterItOrAbsorbALaterDecision() {
        val state = CompanionPresenceState(role, innerThought = "当时的想法", updatedAt = now, provenanceId = "shared-cause")
        val later = CharacterRecordProjection.DecisionMoment("later", now.plusSeconds(60), "阅读", "稍后才决定", "已读", "shared-cause")
        val mind = JSONObject().put("openConcerns", JSONArray().put(JSONObject()
            .put("id", "future-concern").put("createdAt", now.minusSeconds(60).toString())
            .put("updatedAt", now.plusSeconds(60).toString()).put("focus", "后来才改变的挂心")))
        val past = CharacterStateHistory.entries(role, listOf(state), now, mind, listOf(later).filter { it.at <= now })
        assertEquals(1, past.size)
        assertFalse(past.any { it.detail.contains("后来才改变") })
        val laterView = CharacterStateHistory.entries(role, listOf(state), now.plusSeconds(120), JSONObject(), listOf(later))
        assertEquals(2, laterView.size)
        assertTrue(laterView.first { it.moment != null }.decisions.isEmpty())
    }
}
