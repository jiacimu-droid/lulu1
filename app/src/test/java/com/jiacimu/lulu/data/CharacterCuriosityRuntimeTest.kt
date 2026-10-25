package com.jiacimu.lulu.data

import android.content.Context
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
class CharacterCuriosityRuntimeTest {
    private val id = "curiosity-progress-test"
    private val at = Instant.parse("2026-10-10T10:00:00Z")
    private fun begin() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        SharedExperienceTimeline.initialize(ctx)
        CharacterCuriosityRuntime.initialize(ctx)
        CharacterCuriosityRuntime.clear(id)
        listOf("source-1", "source-observation").forEach { source ->
            SharedExperienceTimeline.record(source, id, "数字世界事件", "数字世界",
                "观察到一件事实", at, triggerExtraction = false,
                source = "digital-world", evidenceKind = EventEvidenceKind.Observation)
        }
    }
    private fun realRead(source: String, time: Instant) {
        SharedExperienceTimeline.record(source, id, "实际工具执行", "执行器",
            JSONObject().put("status", "succeeded").put("tool", "read_book")
                .put("result", JSONObject().put("success", true).toString()).toString(),
            time, triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
    }
    private fun proposal(question: String = "故事后面如何发展") =
        JSONObject().put("topic", "想看的故事").put("question", question)
            .put("why", "刚读过正文").put("nextStep", "从真实进度继续读")

    @Test fun manualRemovalTargetsOneCuriosityNotAnotherOrItsVerifiedPast() {
        begin()
        val other = proposal("人物动机是什么")
        CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-1", "第一个问题", at)
        CharacterCuriosityRuntime.recordInquiry(id, other, "source-observation", "第二个问题", at)
        val target = CharacterCuriosityRuntime.visibleQuestions(id).first {
            it.optString("question") == proposal().optString("question")
        }
        assertTrue(CharacterCuriosityRuntime.deleteInterest(id, CharacterCuriosityRuntime.identity(target)))
        assertEquals(1, CharacterCuriosityRuntime.visibleQuestions(id).size)
        assertEquals("人物动机是什么",
            CharacterCuriosityRuntime.visibleQuestions(id).single().optString("question"))
        assertNotNull(SharedExperienceTimeline.eventsByIds(id, listOf("source-1")).firstOrNull())
        assertFalse(CharacterCuriosityRuntime.deleteInterest(id, CharacterCuriosityRuntime.identity(target)))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun onlyActiveExplorationsAppearInCrossSurfaceContext() {
        begin()
        CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-1", "看过书的简介", at)
        assertTrue(CharacterCuriosityRuntime.briefContext(id).contains("尚未实践"))
        realRead("real-source", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true, "实际读了第一章", "real-source", at)
        assertTrue(CharacterCuriosityRuntime.briefContext(id).contains("实际读了第一章"))
        CharacterCuriosityRuntime.releaseInterest(id, proposal().put("status", "dropped"))
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun pastLegacyReadingStillCountsAsActuallyReadWithoutDataLoss() {
        begin()
        SharedExperienceTimeline.record("legacy-read", id, "独自阅读《旧书》", "角色",
            "历史上确实已经完成阅读的正文区间", at, triggerExtraction = false,
            source = "reading:old-book", evidenceKind = EventEvidenceKind.Legacy)
        CharacterCuriosityRuntime.recordInquiry(id, proposal(), "legacy-read",
            "旧版阅读保存的实际记录", at)
        assertEquals(1, CharacterCuriosityRuntime.snapshot(id).getJSONArray("questions").length())
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "已阅读下一段", "legacy-read", at)
        assertEquals(1, CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").length())
    }

    @Test fun observationOrInventedIdDoesNotCompleteExploration() {
        begin()
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "模型说读过了", "invented", at)
        SharedExperienceTimeline.record("online-only", id, "在线感知", "观察",
            "没有读书，仅上线", at, triggerExtraction = false,
            source = "online-awareness", evidenceKind = EventEvidenceKind.Observation)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "想象阅读完成", "online-only", at)
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id)
            .optJSONArray("threads")?.length() ?: 0)
    }

    @Test fun aWishOrFailedToolDoesNotCountAsExploration() {
        begin()
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", false, "失败", "failed", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "silent", true, "想象中的结果", "silent", at)
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id).optJSONArray("threads")?.length() ?: 0)
    }

    @Test fun unansweredQuestionIsNotACompletedExperienceAndCanBeRetracted() {
        begin()
        CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-observation",
            "真的遇到一个不懂的问题", at)
        val state = CharacterCuriosityRuntime.snapshot(id)
        assertEquals(1, state.getJSONArray("questions").length())
        assertEquals(0, state.optJSONArray("threads")?.length() ?: 0)
        CharacterCuriosityRuntime.invalidateEvidence("source-observation")
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id).getJSONArray("questions").length())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun actualFailureIsRememberedButNeverCountsAsSuccess() {
        begin()
        CharacterCuriosityRuntime.recordFailure(id, proposal(), "reading", "该书不存在", at)
        CharacterCuriosityRuntime.recordFailure(id, proposal(), "reading", "该书仍不存在", at.plusSeconds(180))
        val state = CharacterCuriosityRuntime.snapshot(id)
        assertEquals(0, state.optJSONArray("threads")?.length() ?: 0)
        assertEquals(2, state.getJSONArray("failures").getJSONObject(0).getInt("repeats"))
        realRead("real-book-event", at.plusSeconds(3600))
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "成功实际阅读", "real-book-event", at.plusSeconds(3600))
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures").length())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun succeedingInOneQuestionDoesNotClearAnotherSameTopicFailure() {
        begin()
        val main = proposal("章节之后怎么发展")
        val other = proposal("作者用了什么叙事方法")
        // Independent feedback may arrive before either question was persisted.
        CharacterCuriosityRuntime.recordFailure(id, main, "reading", "第一项暂时读不到", at)
        CharacterCuriosityRuntime.recordFailure(id, other, "reading", "第二项网络失败", at)
        assertEquals(2, CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures").length())

        realRead("first-question-success", at.plusSeconds(60))
        CharacterCuriosityRuntime.recordOutcome(id, main, "reading", true,
            "第一项成功", "first-question-success", at.plusSeconds(60))
        val after = CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures")
        assertEquals(1, after.length())
        assertEquals(other.getString("question"), after.getJSONObject(0).getString("question"))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun failureForLinkedThreadIsClearedByMatchingVerifiedReceipt() {
        begin()
        CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-1", "真实看到的问题", at)
        val pending = CharacterCuriosityRuntime.visibleQuestions(id).single()
        val identity = pending.getString("threadId")
        val owned = proposal().put("threadId", identity)
        CharacterCuriosityRuntime.recordFailure(id, owned, "reading", "读书失败", at)
        assertEquals(identity, CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures")
            .getJSONObject(0).getString("threadId"))
        realRead("completed-reading", at.plusSeconds(60))
        CharacterCuriosityRuntime.recordOutcome(id, owned, "reading", true,
            "已实际读到后续内容", "completed-reading", at.plusSeconds(60))
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures").length())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun characterCanDropAnInterestWithoutErasingItsRealPast() {
        begin()
        realRead("past-chapter", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "实际读过第一章", "past-chapter", at)
        CharacterCuriosityRuntime.releaseInterest(id,
            proposal().put("status", "dropped"), at.plusSeconds(180))
        val thread = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("dropped", thread.getString("status"))
        assertEquals(1, thread.getJSONArray("steps").length())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun successfulActionPersistsOnceAndDeletionRetractsOnlyItsSource() {
        begin()
        realRead("s1", at)
        realRead("s2", at.plusSeconds(3600))
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true, "真的读了第一章", "s1", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true, "重复回调", "s1", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal("主角下一步做什么"), "reading",
            true, "真的读了第二章", "s2", at.plusSeconds(3600))
        assertEquals(2, CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads")
            .getJSONObject(0).getJSONArray("steps").length())
        CharacterCuriosityRuntime.invalidateEvidence("s2")
        val remaining = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("故事后面如何发展", remaining.getString("question"))
        CharacterCuriosityRuntime.invalidateEvidence("s1")
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").length())
        CharacterCuriosityRuntime.clear(id)
    }
}
