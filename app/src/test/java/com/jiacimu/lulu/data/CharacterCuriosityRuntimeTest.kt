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

    @Test fun initializationRebindsPreferencesAfterAnApplicationSandboxChanges() {
        val context = RuntimeEnvironment.getApplication() as Context
        val real = context.getSharedPreferences("lulu_curiosity_threads_v1", Context.MODE_PRIVATE)
        val other = context.getSharedPreferences("lulu_curiosity_wrong_application", Context.MODE_PRIVATE)
        real.edit().putString(id, JSONObject().put("origin", "current").toString()).commit()
        other.edit().putString(id, JSONObject().put("origin", "old").toString()).commit()
        val simulatedOldApp = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String?, mode: Int): android.content.SharedPreferences = other
        }
        try {
            CharacterCuriosityRuntime.initialize(simulatedOldApp)
            assertEquals("old", CharacterCuriosityRuntime.snapshot(id).getString("origin"))
            CharacterCuriosityRuntime.initialize(context)
            assertEquals("current", CharacterCuriosityRuntime.snapshot(id).getString("origin"))
        } finally {
            CharacterCuriosityRuntime.initialize(context)
            CharacterCuriosityRuntime.clear(id)
            other.edit().remove(id).commit()
        }
    }

    @Test fun ownerCanResumePreviousDormantQuestionWithItsOriginalIdentity() {
        begin()
        val archived = JSONObject().put("threadId", "archived-thread")
            .put("topic", "想看的故事").put("question", "故事后面如何发展")
            .put("observedId", "source-1")
        val ctx = RuntimeEnvironment.getApplication() as Context
        val prefs = ctx.getSharedPreferences("lulu_curiosity_threads_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(id, JSONObject().put("dormantQuestions",
            org.json.JSONArray().put(archived)).toString()).commit())
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id,
            proposal().put("threadId", "archived-thread"),
            "source-1", "重新在意自己曾经的疑问", at.plusSeconds(90)))
        assertEquals("archived-thread",
            CharacterCuriosityRuntime.visibleQuestions(id).single().optString("threadId"))
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("dormantQuestions").length())
        CharacterCuriosityRuntime.clear(id)
    }

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

    @Test fun characterCanSettleSharedInterestWithoutErasingItsRealPast() {
        begin()
        realRead("past-chapter", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "实际读过第一章", "past-chapter", at)
        CharacterCuriosityRuntime.settleInterest(id,
            proposal().put("status", "satisfied"), at.plusSeconds(180))
        val thread = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("satisfied", thread.getString("status"))
        assertEquals(1, thread.getJSONArray("steps").length())
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun delayedVerifiedResultCannotReopenAnExplicitlyDroppedInterestButNewInquiryCan() {
        begin()
        realRead("first-completed", at)
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "先前读完一章", "first-completed", at)
        val threadId = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads")
            .getJSONObject(0).getString("threadId")
        val selected = proposal().put("threadId", threadId)
        CharacterCuriosityRuntime.releaseInterest(id,
            JSONObject(selected.toString()).put("status", "dropped"), at.plusSeconds(60))

        // The original action's callback arrives after the character has chosen to stop.
        realRead("late-completed", at.plusSeconds(120))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal("旧任务迟到的后续问题").put("threadId", threadId),
            "reading", true, "确实完成但已不想继续", "late-completed", at.plusSeconds(120))
        val closed = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").getJSONObject(0)
        assertEquals("dropped", closed.getString("status"))
        assertEquals("故事后面如何发展", closed.getString("question"))
        assertEquals(2, closed.getJSONArray("steps").length())
        assertEquals("确实完成但已不想继续",
            closed.getJSONArray("steps").getJSONObject(1).getString("receipt"))
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        CharacterCuriosityRuntime.recordOutcome(id, selected, "reading", true,
            "同一回执重放", "late-completed", at.plusSeconds(180))
        assertEquals(2, CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0).getJSONArray("steps").length())

        // A later, separately observed question is a new choice, not a stale callback.
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id,
            proposal("新的线索值得重新研究").put("threadId", threadId),
            "source-observation", "后来真的发现新线索", at.plusSeconds(240)))
        // An OLD action can also finish after that new inquiry was recorded.
        // Its old source must not consume the new inquiry or reopen the thread.
        realRead("stale-after-new-inquiry", at.plusSeconds(270))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "仍是旧动作的回执", "stale-after-new-inquiry", at.plusSeconds(270))
        assertEquals("dropped", CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0).getString("status"))
        assertEquals(1, CharacterCuriosityRuntime.visibleQuestions(id).size)

        realRead("new-completed", at.plusSeconds(300))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal("新的线索值得重新研究").put("threadId", threadId)
                .put("sourceEvidenceId", "source-observation"),
            "reading", true, "重新探索的实际结果", "new-completed", at.plusSeconds(300))
        val resumed = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").getJSONObject(0)
        assertEquals("exploring", resumed.getString("status"))
        assertEquals("新的线索值得重新研究", resumed.getString("question"))
        // The stale callback was correctly rejected; only the two older
        // verified steps and the new owned result belong to this thread.
        assertEquals(3, resumed.getJSONArray("steps").length())
        assertFalse((0 until resumed.getJSONArray("steps").length()).any {
            resumed.getJSONArray("steps").getJSONObject(it).optString("evidenceId") == "stale-after-new-inquiry"
        })
        assertFalse(resumed.has("releasedAt"))
        assertTrue(CharacterCuriosityRuntime.briefContext(id).contains("重新探索的实际结果"))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun settlingPendingQuestionBeforeFirstReceiptStillSurvivesLateResult() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-1",
            "观察后决定进一步了解", at.plusSeconds(10)))
        val threadId = CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId")
        CharacterCuriosityRuntime.settleInterest(id,
            proposal().put("threadId", threadId).put("status", "satisfied"), at.plusSeconds(20))
        assertEquals(0, CharacterCuriosityRuntime.visibleQuestions(id).size)
        val closedBeforeResult = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("satisfied", closedBeforeResult.getString("status"))
        assertEquals(0, closedBeforeResult.optJSONArray("steps")?.length() ?: 0)

        realRead("pending-late-result", at.plusSeconds(30))
        CharacterCuriosityRuntime.recordOutcome(id, proposal().put("threadId", threadId),
            "reading", true, "迟到但真实的阅读", "pending-late-result", at.plusSeconds(30))
        val closedAfterResult = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("satisfied", closedAfterResult.getString("status"))
        assertEquals(1, closedAfterResult.getJSONArray("steps").length())
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun withdrawingOnePendingClosureDoesNotEraseOtherIndependentClosure() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-1",
            "第一条真实观察", at.plusSeconds(10)))
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal("另一条独立疑问"),
            "source-observation", "第二条真实观察", at.plusSeconds(10)))
        CharacterCuriosityRuntime.releaseInterest(id,
            proposal().put("status", "dropped"), at.plusSeconds(20))
        CharacterCuriosityRuntime.settleInterest(id,
            proposal("另一条独立疑问").put("status", "satisfied"), at.plusSeconds(20))
        assertEquals(2, CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").length())
        CharacterCuriosityRuntime.invalidateEvidence("source-1")
        val remaining = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads")
        assertEquals(1, remaining.length())
        assertEquals("另一条独立疑问", remaining.getJSONObject(0).getString("question"))
        assertEquals("satisfied", remaining.getJSONObject(0).getString("status"))
        CharacterCuriosityRuntime.clear(id)
    }


    @Test fun settlingAnInterestSurvivesReceiptRetractionAndDelayedNewReceipt() {
        begin()
        realRead("settled-first", at.plusSeconds(10))
        realRead("settled-second", at.plusSeconds(20))
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "第一章的真实结果", "settled-first", at.plusSeconds(10))
        CharacterCuriosityRuntime.recordOutcome(id, proposal("第二章的疑问"), "reading", true,
            "第二章的真实结果", "settled-second", at.plusSeconds(20))
        CharacterCuriosityRuntime.settleInterest(id,
            proposal("第二章的疑问").put("status", "satisfied"), at.plusSeconds(30))
        val original = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").getJSONObject(0)
        val threadId = original.getString("threadId")
        val settledAt = original.getString("settledAt")

        // The newest receipt is withdrawn, but an older valid result remains.
        CharacterCuriosityRuntime.invalidateEvidence("settled-second")
        val rolledBack = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("satisfied", rolledBack.getString("status"))
        assertEquals(settledAt, rolledBack.getString("settledAt"))
        assertEquals("第二章的疑问", rolledBack.getString("question"))
        assertEquals(1, rolledBack.getJSONArray("steps").length())
        assertEquals("第一章的真实结果",
            CharacterCuriosityRuntime.lastRealStep(id, rolledBack)?.optString("receipt"))
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))

        // Even with no surviving tool receipt, the explicitly chosen ending
        // must survive so a delayed tool callback cannot reopen it.
        CharacterCuriosityRuntime.invalidateEvidence("settled-first")
        val terminal = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("satisfied", terminal.getString("status"))
        assertEquals(0, terminal.getJSONArray("steps").length())
        assertEquals(settledAt, terminal.getString("settledAt"))
        realRead("settled-late", at.plusSeconds(40))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal("旧动作迟到").put("threadId", threadId),
            "reading", true, "真实但迟到的结果", "settled-late", at.plusSeconds(40))
        assertEquals("satisfied", CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0).getString("status"))
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun droppingAnInterestSurvivesPartialReceiptRetraction() {
        begin()
        realRead("dropped-first", at.plusSeconds(10))
        realRead("dropped-second", at.plusSeconds(20))
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "真实读过第一章", "dropped-first", at.plusSeconds(10))
        CharacterCuriosityRuntime.recordOutcome(id, proposal("不想再读的后续"), "reading", true,
            "真实读过第二章", "dropped-second", at.plusSeconds(20))
        CharacterCuriosityRuntime.releaseInterest(id,
            proposal("不想再读的后续").put("status", "dropped"), at.plusSeconds(30))
        val releasedAt = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0).getString("releasedAt")
        CharacterCuriosityRuntime.invalidateEvidence("dropped-second")
        val remaining = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals("dropped", remaining.getString("status"))
        assertEquals(releasedAt, remaining.getString("releasedAt"))
        assertEquals("不想再读的后续", remaining.getString("question"))
        assertEquals(1, remaining.getJSONArray("steps").length())
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        CharacterCuriosityRuntime.clear(id)
    }


    @Test fun oldToolResultCannotConsumeANewerPendingQuestionOrItsFailure() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(),
            "source-1", "第一次看到的线索", at.plusSeconds(10)))
        val threadId = CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId")
        realRead("first-inquiry-result", at.plusSeconds(20))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "旧问题的第一次真实阅读", "first-inquiry-result", at.plusSeconds(20))
        assertEquals(0, CharacterCuriosityRuntime.visibleQuestions(id).size)

        val newQuestion = proposal("新证据出现后人物为什么改变主意")
            .put("threadId", threadId).put("sourceEvidenceId", "source-observation")
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, newQuestion,
            "source-observation", "新的独立观察", at.plusSeconds(30)))
        CharacterCuriosityRuntime.recordFailure(id, newQuestion, "reading",
            "新问题暂时查不到", at.plusSeconds(35))
        realRead("old-action-late", at.plusSeconds(40))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "旧动作晚到", "old-action-late", at.plusSeconds(40))
        val pending = CharacterCuriosityRuntime.visibleQuestions(id)
        assertEquals(1, pending.size)
        assertEquals(newQuestion.getString("question"), pending.single().getString("question"))
        val before = CharacterCuriosityRuntime.snapshot(id)
        assertEquals(1, before.getJSONArray("threads").getJSONObject(0)
            .getJSONArray("steps").length())
        assertEquals(1, before.getJSONArray("failures").length())
        assertEquals("故事后面如何发展",
            before.getJSONArray("threads").getJSONObject(0).getString("question"))
        // The late tool did execute: do not delete or invent its timeline fact.
        assertNotNull(SharedExperienceTimeline.eventsByIds(id, listOf("old-action-late")).firstOrNull())

        realRead("new-inquiry-result", at.plusSeconds(50))
        CharacterCuriosityRuntime.recordOutcome(id, newQuestion, "reading", true,
            "新问题的真实阅读", "new-inquiry-result", at.plusSeconds(50))
        CharacterCuriosityRuntime.initialize(RuntimeEnvironment.getApplication() as Context)
        assertEquals(0, CharacterCuriosityRuntime.visibleQuestions(id).size)
        val after = CharacterCuriosityRuntime.snapshot(id)
        val thread = after.getJSONArray("threads").getJSONObject(0)
        assertEquals(newQuestion.getString("question"), thread.getString("question"))
        assertEquals("source-observation", thread.getString("observedId"))
        assertEquals(2, thread.getJSONArray("steps").length())
        assertEquals(0, after.getJSONArray("failures").length())
        assertTrue(CharacterCuriosityRuntime.briefContext(id).contains("新问题的真实阅读"))

        // An even later callback from the OLD observation must not roll back
        // the newer inquiry after its pending slot has already been consumed.
        realRead("old-action-even-later", at.plusSeconds(60))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "旧任务再次迟到", "old-action-even-later", at.plusSeconds(60))
        val finalThread = CharacterCuriosityRuntime.snapshot(id)
            .getJSONArray("threads").getJSONObject(0)
        assertEquals(newQuestion.getString("question"), finalThread.getString("question"))
        assertEquals(2, finalThread.getJSONArray("steps").length())
        assertTrue(CharacterCuriosityRuntime.briefContext(id).contains("新问题的真实阅读"))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun revisingAQuestionOnTheSameObservationStillRejectsItsOldPendingAction() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(),
            "source-1", "同一材料引发旧问题", at.plusSeconds(10)))
        val threadId = CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId")
        realRead("same-source-first", at.plusSeconds(20))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "旧问题确实做过", "same-source-first", at.plusSeconds(20))
        val revised = proposal("同一材料的新疑问").put("threadId", threadId)
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, revised,
            "source-1", "仍是同一材料但本人改问法", at.plusSeconds(30)))
        realRead("same-source-old-late", at.plusSeconds(40))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "旧问题的迟到回执", "same-source-old-late", at.plusSeconds(40))
        assertEquals("同一材料的新疑问",
            CharacterCuriosityRuntime.visibleQuestions(id).single().getString("question"))
        assertEquals(1, CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads")
            .getJSONObject(0).getJSONArray("steps").length())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun staleFirstReceiptCannotConsumeANewerPendingQuestionWithoutAThread() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(),
            "source-observation", "新观察产生的待研究问题", at.plusSeconds(10)))
        val ownedId = CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId")
        realRead("late-old-first-result", at.plusSeconds(20))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", ownedId).put("sourceEvidenceId", "source-1"),
            "reading", true, "旧来源的迟到结果", "late-old-first-result", at.plusSeconds(20))
        assertEquals(1, CharacterCuriosityRuntime.visibleQuestions(id).size)
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id)
            .optJSONArray("threads")?.length() ?: 0)

        realRead("owned-first-result", at.plusSeconds(30))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", ownedId).put("sourceEvidenceId", "source-observation"),
            "reading", true, "新问题的真实阅读", "owned-first-result", at.plusSeconds(30))
        CharacterCuriosityRuntime.initialize(RuntimeEnvironment.getApplication() as Context)
        assertEquals(0, CharacterCuriosityRuntime.visibleQuestions(id).size)
        val thread = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").getJSONObject(0)
        assertEquals(ownedId, thread.getString("threadId"))
        assertEquals("source-observation", thread.getString("observedId"))
        assertEquals(1, thread.getJSONArray("steps").length())
        assertEquals("新问题的真实阅读",
            thread.getJSONArray("steps").getJSONObject(0).getString("receipt"))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun unknownExplicitThreadFailureCannotBecomeUnlinkedRetryCue() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(),
            "source-1", "本人真实在意的疑问", at.plusSeconds(10)))
        val ownedId = CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId")
        CharacterCuriosityRuntime.recordFailure(id,
            proposal().put("threadId", "deleted-or-other-character-thread")
                .put("sourceEvidenceId", "source-1"),
            "reading", "不属于本人的旧任务失败", at.plusSeconds(20))
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id)
            .optJSONArray("failures")?.length() ?: 0)
        assertFalse(CharacterCuriosityRuntime.promptSection(id, "测试人物",
            emptyList(), at.plusSeconds(25)).contains("不属于本人的旧任务失败"))

        CharacterCuriosityRuntime.recordFailure(id,
            proposal().put("threadId", ownedId).put("sourceEvidenceId", "source-1"),
            "reading", "本人本次阅读失败", at.plusSeconds(30))
        CharacterCuriosityRuntime.initialize(RuntimeEnvironment.getApplication() as Context)
        val failure = CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures").getJSONObject(0)
        assertEquals(ownedId, failure.getString("threadId"))
        assertEquals("本人本次阅读失败", failure.getString("reason"))
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun deletingPendingCuriosityKeepsTerminalTombstoneAgainstLateReceipt() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id, proposal(), "source-1",
            "本人尚未实践的问题", at.plusSeconds(10)))
        val threadId = CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId")
        assertTrue(CharacterCuriosityRuntime.deleteInterest(id, threadId))
        assertFalse(CharacterCuriosityRuntime.deleteInterest(id, threadId))
        assertTrue(CharacterCuriosityRuntime.visibleQuestions(id).isEmpty())
        val tombstone = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").getJSONObject(0)
        assertEquals("dropped", tombstone.getString("status"))
        assertTrue(tombstone.getBoolean("ownerDeleted"))
        assertEquals(0, tombstone.optJSONArray("steps")?.length() ?: 0)

        realRead("deleted-late-receipt", at.plusSeconds(20))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", threadId).put("sourceEvidenceId", "source-1"),
            "reading", true, "删除后才送达的真实结果", "deleted-late-receipt", at.plusSeconds(20))
        CharacterCuriosityRuntime.initialize(RuntimeEnvironment.getApplication() as Context)
        val after = CharacterCuriosityRuntime.snapshot(id).getJSONArray("threads").getJSONObject(0)
        assertEquals("dropped", after.getString("status"))
        assertEquals(1, after.getJSONArray("steps").length())
        assertTrue(after.getBoolean("ownerDeleted"))
        assertEquals("", CharacterCuriosityRuntime.briefContext(id))
        assertTrue(CharacterCuriosityRuntime.visibleQuestions(id).isEmpty())
        assertNotNull(SharedExperienceTimeline.eventsByIds(id, listOf("deleted-late-receipt")).firstOrNull())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun deletingOneIdenticalQuestionPreservesOtherOwnedSourceAndExploration() {
        begin()
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id,
            proposal().put("threadId", "independent-request-a"),
            "source-1", "第一条独立线索", at.plusSeconds(10)))
        assertTrue(CharacterCuriosityRuntime.recordInquiry(id,
            proposal().put("threadId", "independent-request-b"),
            "source-observation", "第二条独立线索", at.plusSeconds(10)))
        val before = CharacterCuriosityRuntime.visibleQuestions(id)
        assertEquals(2, before.size)
        val first = before.single { it.optString("observedId") == "source-1" }
        val second = before.single { it.optString("observedId") == "source-observation" }
        assertNotEquals(first.getString("threadId"), second.getString("threadId"))
        assertTrue(CharacterCuriosityRuntime.deleteInterest(id, first.getString("threadId")))
        assertEquals(second.getString("threadId"),
            CharacterCuriosityRuntime.visibleQuestions(id).single().getString("threadId"))

        realRead("other-owned-result", at.plusSeconds(30))
        CharacterCuriosityRuntime.recordOutcome(id,
            proposal().put("threadId", second.getString("threadId"))
                .put("sourceEvidenceId", "source-observation"),
            "reading", true, "另一条线索的真实结果", "other-owned-result", at.plusSeconds(30))
        CharacterCuriosityRuntime.initialize(RuntimeEnvironment.getApplication() as Context)
        val state = CharacterCuriosityRuntime.snapshot(id)
        val all = state.getJSONArray("threads")
        assertEquals(2, all.length())
        val dropped = (0 until all.length()).map { all.getJSONObject(it) }
            .single { it.getString("threadId") == first.getString("threadId") }
        val active = (0 until all.length()).map { all.getJSONObject(it) }
            .single { it.getString("threadId") == second.getString("threadId") }
        assertEquals("dropped", dropped.getString("status"))
        assertEquals("exploring", active.getString("status"))
        assertEquals(1, CharacterCuriosityRuntime.activeThreads(id).size)
        assertTrue(CharacterCuriosityRuntime.briefContext(id).contains("另一条线索的真实结果"))
        CharacterCuriosityRuntime.clear(id)
    }
}
