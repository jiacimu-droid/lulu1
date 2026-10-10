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
        CharacterCuriosityRuntime.initialize(RuntimeEnvironment.getApplication() as Context)
        CharacterCuriosityRuntime.clear(id)
    }
    private fun proposal(question: String = "故事后面如何发展") =
        JSONObject().put("topic", "想看的故事").put("question", question)
            .put("why", "刚读过正文").put("nextStep", "从真实进度继续读")

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
        CharacterCuriosityRuntime.recordOutcome(id, proposal(), "reading", true,
            "成功实际阅读", "real-book-event", at.plusSeconds(3600))
        assertEquals(0, CharacterCuriosityRuntime.snapshot(id).getJSONArray("failures").length())
        CharacterCuriosityRuntime.clear(id)
    }

    @Test fun characterCanDropAnInterestWithoutErasingItsRealPast() {
        begin()
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
