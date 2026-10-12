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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterLearningLedgerTest {
    private val character = "learning-ledger-test"
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    @Before fun start() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLearningLedger.initialize(context)
        CharacterLearningLedger.clearCharacter(character)
    }
    @After fun finish() {
        CharacterLearningLedger.clearCharacter(character)
        MemoryTestEnvironment.release()
    }
    private fun note(value: String) = JSONObject().put("topic", "人物动机")
        .put("understanding", value).put("question", "还有别的解释吗")
    private fun reading(id: String, seconds: Long) {
        SharedExperienceTimeline.record(id, character, "独自阅读《故事》", "角色", "确实读过一段",
            now.plusSeconds(seconds), triggerExtraction = false, source = "reading:story",
            evidenceKind = EventEvidenceKind.Observation)
    }
    @Test fun learnedIdeaRequiresRealReadingNotUserClaim() {
        assertFalse(CharacterLearningLedger.recordReading(character, "imagined", note("动机未必单一")))
        SharedExperienceTimeline.record("user-said", character, "私聊", "用户", "读过书",
            now, triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        assertFalse(CharacterLearningLedger.recordReading(character, "user-said", note("动机未必单一")))
        reading("read-one", 1)
        assertTrue(CharacterLearningLedger.recordReading(character, "read-one", note("动机未必单一")))
        assertFalse(CharacterLearningLedger.recordReading(character, "read-one", note("第二遍相同总结")))
        assertTrue(CharacterLearningLedger.context(character, "故事人物动机").contains("动机未必单一"))
    }
    @Test fun revisionNeedsOlderValidMaterialAndDeletionRestoresEarlierView() {
        reading("read-two", 2)
        reading("read-three", 3)
        assertTrue(CharacterLearningLedger.recordReading(character, "read-two", note("最初以为是逃跑")))
        assertFalse(CharacterLearningLedger.recordReading(character, "read-three",
            note("或许也为了保护").put("revisesEvidenceId", "fake")))
        assertTrue(CharacterLearningLedger.recordReading(character, "read-three",
            note("或许也为了保护").put("revisesEvidenceId", "read-two")))
        assertFalse(CharacterLearningLedger.context(character, "人物动机").contains("最初以为是逃跑"))
        SharedExperienceTimeline.deleteEvent("read-three")
        assertTrue(CharacterLearningLedger.context(character, "人物动机").contains("最初以为是逃跑"))
        SharedExperienceTimeline.deleteEvent("read-two")
        assertEquals("", CharacterLearningLedger.context(character, "人物动机"))
    }
    @Test fun userFeedbackReferencesRealAttemptAndCanBeRetracted() {
        reading("read-feedback", 10)
        CharacterLearningLedger.recordReading(character, "read-feedback", note("说话不必长篇大论"))
        val proposal = JSONObject().put("sourceEvidenceId", "read-feedback")
            .put("howApplied", "尝试用更短的回复")
        SharedExperienceTimeline.record("delivered-message", character, "私聊", "角色",
            "其实没必要解释这么多。", now.plusSeconds(20), triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        assertTrue(CharacterLearningLedger.recordApplication(character, proposal,
            "delivered-message", "message", true, "已实际送达"))
        SharedExperienceTimeline.record("user-feedback", character, "私聊", "用户",
            "我喜欢你这样说话，更自然了。", now.plusSeconds(30), triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        val feedback = JSONObject().put("sourceEvidenceId", "read-feedback")
            .put("actionEvidenceId", "delivered-message").put("evaluation", "helped")
        assertTrue(CharacterLearningLedger.recordFeedback(character, feedback, "user-feedback"))
        assertFalse(CharacterLearningLedger.recordFeedback(character, feedback, "user-feedback"))
        assertTrue(CharacterLearningLedger.context(character, "人物动机").contains("用户真实反馈"))
        SharedExperienceTimeline.deleteEvent("user-feedback")
        assertFalse(CharacterLearningLedger.context(character, "人物动机").contains("用户真实反馈"))
        SharedExperienceTimeline.record("user-negative", character, "私聊", "用户",
            "别这么说了，我不喜欢这种表达。", now.plusSeconds(40), triggerExtraction = false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        assertFalse(CharacterLearningLedger.recordFeedback(character,
            feedback, "user-negative"))
        assertTrue(CharacterLearningLedger.recordFeedback(character,
            JSONObject(feedback.toString()).put("evaluation", "unhelpful"), "user-negative"))
        assertTrue(CharacterLearningLedger.context(character, "人物动机").contains("不适合"))
    }

    @Test fun changingOldReadingRevisionWithdrawsDependentInterpretation() {
        reading("read-older", 11)
        reading("read-newer", 12)
        assertTrue(CharacterLearningLedger.recordReading(character, "read-older", note("先这样理解")))
        assertTrue(CharacterLearningLedger.recordReading(character, "read-newer",
            note("后来有了新看法").put("revisesEvidenceId", "read-older")))
        // Editing the source in place revokes the previous source revision.
        SharedExperienceTimeline.record("read-older", character, "独自阅读《故事》", "角色",
            "重新修订了原始文本的证据", now.plusSeconds(11),
            triggerExtraction = false, source = "reading:story")
        assertEquals("", CharacterLearningLedger.context(character, "人物动机"))
    }

    @Test fun groundedUserCounterEvidenceRemainsUncertainAndSurvivesRestarts() {
        reading("read-challenged", 50)
        CharacterLearningLedger.recordReading(character, "read-challenged",
            note("这位人物好像总是在回避冲突"))
        SharedExperienceTimeline.record("counter-user", character, "私聊", "用户",
            "不对，我认为他其实是想保护别人。", now.plusSeconds(60),
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        val challenge = JSONObject().put("sourceEvidenceId", "read-challenged")
            .put("issue", "可能误把保护他人理解成回避冲突")
        assertTrue(CharacterLearningLedger.recordChallenge(character, challenge, "counter-user"))
        assertFalse(CharacterLearningLedger.recordChallenge(character, challenge, "counter-user"))
        assertTrue(CharacterLearningLedger.context(character, "人物动机").contains("待核对的争议"))
        assertTrue(CharacterLearningLedger.growthTrace(character).any {
            it.label == "理解受到新的质疑" && it.evidenceId == "counter-user"
        })
        SharedExperienceTimeline.deleteEvent("counter-user")
        assertFalse(CharacterLearningLedger.context(character, "人物动机").contains("待核对的争议"))
    }

    @Test fun actualExecutionIsAnAttemptNotAutomaticExpertise() {
        reading("read-four", 4)
        CharacterLearningLedger.recordReading(character, "read-four", note("先听对方说完"))
        val application = JSONObject().put("sourceEvidenceId", "read-four")
            .put("howApplied", "尝试先听完再回应")
        assertFalse(CharacterLearningLedger.recordApplication(character, application,
            "no-result", "message", true, "已经成功"))
        SharedExperienceTimeline.record("real-result", character, "角色日程", "角色", "真的发出回复",
            now.plusSeconds(8), triggerExtraction = false, source = "action-result",
            evidenceKind = EventEvidenceKind.ToolResult)
        assertFalse(CharacterLearningLedger.recordApplication(character, application,
            "real-result", "message", false, "失败"))
        assertTrue(CharacterLearningLedger.recordApplication(character, application,
            "real-result", "message", true, "真的发出回复"))
        assertTrue(CharacterLearningLedger.context(character, "人物动机").contains("已尝试实践"))
        SharedExperienceTimeline.deleteEvent("real-result")
        assertFalse(CharacterLearningLedger.context(character, "人物动机").contains("已尝试实践"))
    }
}
