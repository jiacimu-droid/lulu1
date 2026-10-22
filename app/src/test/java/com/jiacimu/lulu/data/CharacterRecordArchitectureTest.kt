package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.heartVoiceParagraphs
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

    @Test fun repeatedHeartVoiceSnapshotsAreOneReadableMomentNotTwoCards() {
        val thought = "刚刚那个星星眼终于显示出来了，她一看效果就去继续调试。\\n有点想笑，等她忙完再聊吧。"
        val earlier = CompanionPresenceState(role, innerThought = thought,
            mood = "轻松好奇", updatedAt = now)
        val later = CompanionPresenceState(role, innerThought = thought,
            mood = "轻松好奇", statusText = "在云汀平台坐着吹风",
            updatedAt = now.plusSeconds(18))
        val rows = CharacterStateHistory.entries(role, listOf(earlier, later),
            now.plusSeconds(120), JSONObject(), emptyList())
        assertEquals(1, rows.size)
        assertTrue(rows.single().detail.contains("星星眼"))
        assertTrue(rows.single().detail.contains("在云汀平台"))
        assertEquals(1, rows.single().mergedMomentTimes.size)
    }

    @Test fun aRealTurnInPrivateThoughtMustRemainASeparateMoment() {
        val earlier = CompanionPresenceState(role, innerThought = "还想看看屏幕上那个表情会不会动。",
            updatedAt = now)
        val later = CompanionPresenceState(role, innerThought = "风太冷了，还是回家暖和一会儿吧。",
            updatedAt = now.plusSeconds(23))
        val rows = CharacterStateHistory.entries(role, listOf(earlier, later),
            now.plusSeconds(120), JSONObject(), emptyList())
        assertEquals(2, rows.size)
    }

    @Test fun parallelMindFieldsForOneEvidenceDoNotRestateAuthoredVoice() {
        SharedExperienceTimeline.record("one-inner-event", role, "独处", "云汀平台", "在平台吹风",
            now, triggerExtraction = false, source = "digital-world",
            evidenceKind = EventEvidenceKind.Observation)
        val voice = "今天吹风有点舒服，先在这里静静坐一会儿。"
        val mind = JSONObject().put("thoughts", JSONArray().put(JSONObject()
            .put("thought", voice).put("evidenceId", "one-inner-event")
            .put("at", now.plusSeconds(8).toString())))
        val snapshot = CompanionPresenceState(role, innerThought = voice,
            updatedAt = now, provenanceId = "one-inner-event")
        // The separately persisted causal assessment is a projection of the
        // same source, even when the timestamps differ by a few seconds.
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = role, evidenceId = "one-inner-event",
            appraisal = JSONObject().put("meaning", "坐着吹风，暂时放松一下"),
            innerLife = null, innerThoughtBasis = null,
            selectedAction = "silent", innerThought = voice,
            reason = "在自己的生活里歇一歇", now = now.plusSeconds(12))
        mind.put("causalTransitions",
            CharacterInnerLifeStore.snapshot(role).optJSONArray("causalTransitions"))
        val rows = CharacterStateHistory.entries(role, listOf(snapshot),
            now.plusSeconds(120), mind, emptyList())
        assertEquals(1, rows.size)
        assertEquals(1, Regex(Regex.escape(voice)).findAll(rows.single().detail).count())
        assertTrue(rows.single().extraDeletions.any { it.first == "transition" })
        CharacterStateHistory.delete(role, rows.single())
        assertEquals(0, CharacterInnerLifeStore.snapshot(role)
            .optJSONArray("causalTransitions")?.length() ?: 0)
    }
    @Test fun sameGenerationCreatesOneCompleteMomentAcrossPresenceDecisionAndInnerStores() {
        SharedExperienceTimeline.record("choice-source", role, "数字世界", "云汀平台",
            "江渡在平台吹风并决定继续阅读", now, triggerExtraction = false,
            source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        val voice = "晚风吹得人有点想笑，刚刚那个星星眼倒是挺有趣。回头还想看看能不能动得更自然。"
        val mind = JSONObject()
            .put("causalTransitions", JSONArray().put(JSONObject()
                .put("evidenceId", "choice-source")
                .put("innerThought", voice)
                .put("selectedAction", "digital_world")
                .put("at", now.toString())))
            .put("thoughts", JSONArray().put(JSONObject()
                .put("evidenceId", "choice-source")
                .put("thought", voice)
                .put("at", now.plusSeconds(10).toString())))
        val current = CompanionPresenceState(role, innerThought = voice,
            mood = "轻松好奇 · 轻松好奇", statusText = "在平台坐着吹风",
            gesture = "江渡在云汀平台停留", updatedAt = now.plusSeconds(14))
        val decision = CharacterRecordProjection.DecisionMoment("choice", now.plusSeconds(8),
            "在数字世界活动", "想享受一会儿晚风", "江渡在平台停留", "choice-source")
        val rows = CharacterStateHistory.entries(role, listOf(current),
            now.plusSeconds(90), mind, listOf(decision))
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(1, row.decisions.size)
        assertEquals(1, Regex(Regex.escape(voice)).findAll(row.detail).count())
        val presented = CharacterStateHistory.display(row)
        assertEquals(voice, presented.voice)
        assertEquals("轻松好奇", presented.mood)
        assertEquals("在平台坐着吹风", presented.status)
        assertEquals("江渡在云汀平台停留", presented.gesture)
        assertTrue(presented.otherThoughts.none { it.second == voice })
    }

    @Test fun differentStructuredThoughtFromSameCallStaysInsideOneMoment() {
        val moment = CompanionPresenceState(role,
            innerThought = "看着她调试视频，我想坐在平台上歇一会儿。",
            updatedAt = now.plusSeconds(7), provenanceId = "cycle-id")
        val primary = CharacterStateHistoryRow("moment", moment.updatedAt, "轻松",
            moment.innerThought, moment, evidenceIds = listOf("cycle-id"),
            deletionKind = "moment")
        val supporting = CharacterStateHistoryRow("transition", now, "做出行动选择",
            "顺便想起来还没看完的那本小说。", evidenceIds = listOf("cycle-id"),
            deletionKind = "transition", deletionKey = "cycle-id")
        val rows = CharacterStateHistory.compactRepeatedMoments(listOf(primary, supporting))
        assertEquals(1, rows.size)
        val shown = CharacterStateHistory.display(rows.single())
        assertTrue(shown.voice.contains("调试视频"))
        assertTrue(shown.otherThoughts.any { it.second.contains("小说") })
    }

    @Test fun sameEvidenceButGenuinelyNewThoughtIsAnotherMoment() {
        val first = CompanionPresenceState(role, innerThought = "这会儿听着风声，感觉坐在这里还挺自在。",
            updatedAt = now, provenanceId = "shared-wind")
        val next = CompanionPresenceState(role, innerThought = "突然想起来明天的约定，还是先回去准备一下。",
            updatedAt = now.plusSeconds(14), provenanceId = "shared-wind")
        val rows = CharacterStateHistory.entries(role, listOf(first, next),
            now.plusSeconds(90), JSONObject(), emptyList())
        assertEquals(2, rows.size)
    }

    @Test fun longInnerVoiceUsesNaturalParagraphsWithoutLosingItsWords() {
        val prose = (1..7).joinToString("") {
            "夜风吹来时，他忽然想到那个期待已久的测试结果，也想起刚刚在聊天里看到的消息。"
        }
        val paragraphs = heartVoiceParagraphs(prose)
        assertTrue(paragraphs.size > 1)
        assertEquals(prose, paragraphs.joinToString(""))
    }

    @Test fun onePsychologicalEventKeepsConcurrentIdeasAndExecutesNoImaginedAction() {
        val source = "mind-choice-2026"
        SharedExperienceTimeline.record(source, role, "私聊", "用户", "今天有一点不开心",
            now, triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val appraisal = JSONObject().put("privateIdeas", JSONArray()
            .put("想逗她笑").put("想先哄哄她"))
        val voice = "先想说句玩笑话。可她今天已经不开心了，还是先认真听她说吧。"
        CharacterInnerLifeStore.recordCausalTransition(role, source,
            appraisal = appraisal, innerLife = null, innerThoughtBasis = null,
            selectedAction = "silent", innerThought = voice,
            reason = "想给她留一点空间", now = now.plusSeconds(4))
        val transition = CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("causalTransitions").getJSONObject(0)
        assertEquals(2, transition.getJSONObject("appraisal")
            .getJSONArray("privateIdeas").length())
        assertEquals("silent", transition.getString("selectedAction"))
        assertEquals(0, CharacterRecordProjection.decisions(role).size)

        val present = CompanionPresenceState(role, innerThought = voice,
            momentReflection = CharacterMomentSnapshot.reflection(null,
                appraisal = appraisal, innerThought = voice),
            updatedAt = now.plusSeconds(7), provenanceId = source)
        val rows = CharacterStateHistory.entries(role, listOf(present), now.plusSeconds(30))
        assertEquals(1, rows.size)
        val displayed = CharacterStateHistory.display(rows.single())
        assertEquals(voice, displayed.voice)
        assertTrue(displayed.otherThoughts.any { it.second == "想逗她笑" })
        assertTrue(displayed.otherThoughts.any { it.second == "想先哄哄她" })
        assertTrue(displayed.otherThoughts.all { it.first == "想法" })
        // A subjective thought is neither a tool receipt nor a new real event.
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role, listOf(source)).size)
        CharacterStateHistory.delete(role, rows.single())
        assertEquals(0, CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("causalTransitions").length())
        assertEquals(1, SharedExperienceTimeline.eventsByIds(role, listOf(source)).size)
    }
}
