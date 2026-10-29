package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
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
class CharacterCausalTraceTest {
    private lateinit var context: Context
    private val a = "causal-trace-person-a"
    private val b = "causal-trace-person-b"
    private val at = Instant.parse("2026-10-28T08:00:00Z")

    @Before fun setup() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        PerceptionStimulusLedger.initialize(context)
        PerceptionStimulusLedger.clear(a)
        PerceptionStimulusLedger.clear(b)
        CharacterInnerLifeStore.clear(a)
        CharacterInnerLifeStore.clear(b)
    }

    @After fun teardown() {
        CharacterInnerLifeStore.clear(a)
        CharacterInnerLifeStore.clear(b)
        PerceptionStimulusLedger.clear(a)
        PerceptionStimulusLedger.clear(b)
        MemoryTestEnvironment.release()
    }

    private fun source(role: String, id: String, content: String = "今天想看点新东西") {
        SharedExperienceTimeline.record(id, role, "私聊", "用户", content, at,
            triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
    }

    private fun receipt(role: String, id: String, cause: String, tool: String, status: String,
        success: Boolean, summary: String, revision: Long = 1L, native: String = "") {
        val result = JSONObject().put("success", success).put("summary", summary)
        if (native.isNotBlank()) result.put("evidenceId", native)
        SharedExperienceTimeline.record(id, role, "实际工具执行", "执行器",
            JSONObject().put("tool", tool).put("requestId", id)
                .put("causeEvidenceId", cause).put("causeEvidenceRevision", revision)
                .put("status", status).put("result", result.toString()).toString(),
            at.plusSeconds(10), triggerExtraction = false,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult, taskId = id)
    }

    private fun event(role: String, id: String) =
        SharedExperienceTimeline.eventsByIds(role, listOf(id)).single()

    @Test fun sourceThoughtDecisionToolReceiptAndPerceptionStaySeparateForEachPerson() {
        source(a, "trace-a-source")
        source(b, "trace-b-source", "B独有的秘密")
        receipt(a, "trace-a-tool", "trace-a-source", "web_search", "succeeded", true, "找到了资料")
        receipt(b, "trace-b-tool", "trace-b-source", "web_search", "failed", false, "B的查询失败")
        val mind = JSONObject()
            .put("thoughts", JSONArray().put(JSONObject().put("evidenceId", "trace-a-source")
                .put("thought", "想看看再决定是否分享").put("status", "held")))
            .put("decisions", JSONArray().put(JSONObject().put("causalEvidenceId", "trace-a-source")
                .put("selected", "tool").put("reason", "先查证再说")
                .put("outcome", "准备搜索")))
        val mixed = SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b)
        val before = CharacterCausalTrace.inspect(context, a, event(a, "trace-a-source"), mixed, mind)
        val titles = before.map { it.title }
        assertTrue(titles.any { it.contains("本人想法") })
        assertTrue(titles.any { it.contains("本人选择") })
        assertTrue(titles.any { it.contains("执行器确认成功") })
        assertTrue(before.any { it.detail.contains("尚无本人感知检查点") })
        assertFalse(before.any { it.detail.contains("B的查询失败") || it.detail.contains("B独有") })
        assertTrue(PerceptionStimulusLedger.claim(context, a,
            CharacterPerceptionContext.stimulus(event(a, "trace-a-tool"))))
        val after = CharacterCausalTrace.inspect(context, a, event(a, "trace-a-source"), mixed, mind)
        assertTrue(after.any { it.detail.contains("感知检查点已记录") })
        assertFalse(PerceptionStimulusLedger.hasSeen(context, b, "trace-a-tool"))
        assertTrue(CharacterCausalTrace.inspect(context, b, event(a, "trace-a-source"), mixed, mind).isEmpty())
    }

    @Test fun rejectedRouterRequestKeepsActualSourceRevisionWithoutPretendingExecution() = runBlocking {
        source(a, "trace-denied-source")
        val response = ToolRouter.execute(context, a, "nonexistent_trace_capability", JSONObject(),
            requestId = "trace-denied-request", causeEvidenceId = "trace-denied-source",
            reason = "先确认是否能使用")
        assertTrue(JSONObject(response).optString("error").contains("未知能力"))
        val rejection = event(a, "tool-rejected-$a-trace-denied-request")
        val payload = JSONObject(rejection.content)
        assertEquals("rejected", payload.optString("stage"))
        assertEquals("trace-denied-source", payload.optString("causeEvidenceId"))
        assertEquals(1L, payload.optLong("causeEvidenceRevision"))
        assertTrue(CharacterCausalTrace.inspect(context, a, event(a, "trace-denied-source"),
            SharedExperienceTimeline.all(a), JSONObject()).any { it.title.contains("执行前被拒绝") })
        // Editing the source must not make the old rejected request look as
        // though it was caused by the new text.
        source(a, "trace-denied-source", "改口后不要再查询")
        val edited = CharacterCausalTrace.inspect(context, a, rejection,
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(edited.any { it.detail.contains("来源已被编辑") })
        SharedExperienceTimeline.deleteEvent("trace-denied-source")
        val withdrawn = CharacterCausalTrace.inspect(context, a, rejection,
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(withdrawn.any { it.detail.contains("原始依据已撤回") })
    }

    @Test fun characterChosenDecisionIsValidCausalEvidenceButAnotherPersonsDecisionIsNot() = runBlocking {
        val own = "trace-role-choice-a"
        val foreign = "trace-role-choice-b"
        SharedExperienceTimeline.record(own, a, "原住民·到点自主决定", "角色",
            "本人选择发布动态；尚未执行", at, triggerExtraction = false,
            source = "resident-due-decision",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        SharedExperienceTimeline.record(foreign, b, "原住民·到点自主决定", "角色",
            "B自己选择的私人行动", at, triggerExtraction = false,
            source = "resident-due-decision",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        val ownId = "trace-own-choice-request"
        val foreignId = "trace-foreign-choice-request"
        ToolRouter.execute(context, a, "nonexistent_trace_capability", JSONObject(),
            requestId = ownId, causeEvidenceId = own, reason = "模型选择后的尝试")
        ToolRouter.execute(context, a, "nonexistent_trace_capability", JSONObject(),
            requestId = foreignId, causeEvidenceId = foreign, reason = "不能借用B的决定")
        val ownReceipt = event(a, "tool-rejected-$a-$ownId")
        val otherReceipt = event(a, "tool-rejected-$a-$foreignId")
        assertEquals(own, JSONObject(ownReceipt.content).optString("causeEvidenceId"))
        assertEquals(1L, JSONObject(ownReceipt.content).optLong("causeEvidenceRevision"))
        assertEquals("", JSONObject(otherReceipt.content).optString("causeEvidenceId"))
        val lines = CharacterCausalTrace.inspect(context, a, event(a, own),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(lines.any { it.evidenceId == ownReceipt.id &&
            it.title.contains("执行前被拒绝") })
        assertFalse(lines.any { it.evidenceId == otherReceipt.id })
        SharedExperienceTimeline.deleteEvent(own)
        assertTrue(CharacterCausalTrace.inspect(context, a, event(b, foreign),
            SharedExperienceTimeline.all(a), JSONObject()).isEmpty())
        assertTrue(CharacterCausalTrace.inspect(context, a, ownReceipt,
            SharedExperienceTimeline.all(a), JSONObject())
            .any { it.detail.contains("原始依据已撤回") })
    }

    @Test fun pendingFailureAndNativeResultAreNotMisreportedAsCompletedGoals() {
        source(a, "trace-state-source")
        receipt(a, "trace-pending", "trace-state-source", "web_search", "pending", true, "云端处理中")
        receipt(a, "trace-failed", "trace-state-source", "web_search", "failed", false, "网络不可用")
        receipt(a, "trace-waiting", "trace-state-source", "capture_screen", "waiting_user", false, "需要授权")
        SharedExperienceTimeline.record("trace-inner-pending", a, "实际工具执行", "执行器",
            JSONObject().put("tool", "cloud_task").put("status", "succeeded")
                .put("causeEvidenceId", "trace-state-source")
                .put("result", JSONObject().put("success", true).put("status", "pending")
                    .put("summary", "任务已经提交，但结果尚未生成").toString()).toString(),
            at.plusSeconds(12), triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
        val mind = JSONObject()
        val lines = CharacterCausalTrace.inspect(context, a, event(a, "trace-state-source"),
            SharedExperienceTimeline.all(a), mind)
        assertTrue(lines.any { it.title.contains("结果尚未证实") })
        assertTrue(lines.any { it.evidenceId == "trace-inner-pending" &&
            it.title.contains("结果尚未证实") })
        assertTrue(lines.any { it.title.contains("执行失败") })
        assertTrue(lines.any { it.title.contains("等待授权") })
        assertFalse(lines.any { it.title.contains("执行器确认成功") })
        SharedExperienceTimeline.deleteEvent("trace-failed")
        val withoutDeleted = CharacterCausalTrace.inspect(context, a, event(a, "trace-state-source"),
            SharedExperienceTimeline.all(a), mind)
        assertFalse(withoutDeleted.any { it.evidenceId == "trace-failed" })
    }

    @Test fun sharedTaskIdDoesNotClaimDirectCauseWithoutMatchingSourceId() {
        SharedExperienceTimeline.record("trace-task-source", a, "日程", "角色", "同一日程的原始记录",
            at, triggerExtraction = false, source = "resident-day",
            evidenceKind = EventEvidenceKind.Observation, taskId = "shared-task")
        SharedExperienceTimeline.record("trace-task-receipt", a, "实际工具执行", "执行器",
            JSONObject().put("tool", "web_search").put("status", "failed")
                .put("causeEvidenceId", "some-other-event")
                .put("result", JSONObject().put("success", false)
                    .put("error", "另一个动作的失败").toString()).toString(),
            at.plusSeconds(5), triggerExtraction = false,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult,
            taskId = "shared-task")
        val lines = CharacterCausalTrace.inspect(context, a, event(a, "trace-task-source"),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(lines.any { it.title.contains("同任务记录") &&
            it.detail.contains("不证明由当前事件直接触发") })
    }

    @Test fun residentScheduleHasExactDecisionProvenanceWithoutClaimingExecution() {
        val sourceId = "trace-scheduled-daily-a"
        fun dailyDecision(content: String) = SharedExperienceTimeline.record(
            sourceId, a, "原住民·每日自我安排", "角色", content, at,
            triggerExtraction = false, source = "resident-daily",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        dailyDecision("本人今天安排明天分享花草")
        source(b, "trace-scheduled-daily-b", "B的独立安排")
        val now = Instant.now()
        val plan = RoleDayScheduleStore.arrange(context, a, "整理花草观察",
            "想等整理好再分享", now.plusSeconds(7200), now,
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "观察笔记"),
            originEvidenceId = sourceId, originEvidenceRevision = 1L)
        try {
            val stored = RoleDayScheduleStore.plans(context, a).single { it.id == plan.id }
            assertEquals(sourceId, stored.originEvidenceId)
            assertEquals(1L, stored.originEvidenceRevision)
            val lines = CharacterCausalTrace.inspect(context, a, event(a, sourceId),
                SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b), JSONObject())
            assertTrue(lines.any { it.title.contains("已排入日程") &&
                it.detail.contains("原活动没有在此处被证明执行成功") })
            assertFalse(lines.any { it.title.contains("执行器确认成功") })
            val moved = RoleDayScheduleStore.reschedule(context, a, plan.id,
                now.plusSeconds(10800), "本人选择晚一点", now, expectedDueAt = plan.dueAt)
            assertEquals(sourceId, moved.originEvidenceId)
            assertEquals(1L, moved.originEvidenceRevision)
            assertTrue(RoleDayScheduleStore.cancel(context, a, plan.id,
                "本人取消今天的安排", now, expectedDueAt = moved.dueAt))
            val cancelled = CharacterCausalTrace.inspect(context, a, event(a, sourceId),
                SharedExperienceTimeline.all(a), JSONObject())
            assertTrue(cancelled.any { it.title.contains("日程已取消") })
            assertFalse(CharacterCausalTrace.inspect(context, b, event(b, "trace-scheduled-daily-b"),
                SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b), JSONObject())
                .any { it.title.contains("已排入日程") })
            dailyDecision("后来修改了当天计划的文字")
            val edited = CharacterCausalTrace.inspect(context, a, event(a, sourceId),
                SharedExperienceTimeline.all(a), JSONObject())
            assertTrue(edited.any { it.title.contains("旧版本的日程") &&
                it.detail.contains("不能用新内容解释旧安排") })
            SharedExperienceTimeline.deleteEvent(sourceId)
            assertTrue(CharacterCausalTrace.inspect(context, a,
                event(b, "trace-scheduled-daily-b"), SharedExperienceTimeline.all(a),
                JSONObject()).isEmpty())
        } finally {
            RoleDayScheduleStore.remove(context, a, plan.id)
        }
    }

    @Test fun originalResultDeletionAndSourceWithdrawalNeverReviveOldEvidence() {
        source(a, "trace-origin")
        SharedExperienceTimeline.record("trace-native", a, "独自阅读", "角色", "真正读到的材料",
            at.plusSeconds(2), triggerExtraction = false,
            source = "reading:story", evidenceKind = EventEvidenceKind.Observation)
        receipt(a, "trace-receipt", "trace-origin", "read_book", "succeeded", true,
            "已读到材料", native = "trace-native")
        val present = CharacterCausalTrace.inspect(context, a, event(a, "trace-receipt"),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(present.any { it.title == "原生结果记录" && it.evidenceId == "trace-native" })
        val oldReceipt = event(a, "trace-receipt")
        SharedExperienceTimeline.deleteEvent("trace-native")
        // The authoritative timeline retracts tool receipts that only
        // summarized this deleted native effect; the diagnostic cannot revive it.
        assertTrue(SharedExperienceTimeline.eventsByIds(a, listOf("trace-receipt")).isEmpty())
        assertTrue(CharacterCausalTrace.inspect(context, a, oldReceipt,
            SharedExperienceTimeline.all(a), JSONObject()).isEmpty())
        val original = event(a, "trace-origin")
        SharedExperienceTimeline.deleteEvent("trace-origin")
        assertTrue(CharacterCausalTrace.inspect(context, a, original,
            SharedExperienceTimeline.all(a), JSONObject()).isEmpty())
    }
    @Test fun searchResultHasSameFollowThroughFromOriginAndReceiptWithoutClaimingGoalCompletion() {
        source(a, "trace-search-origin", "想搜索后再分享")
        SharedExperienceTimeline.record("trace-search-native", a, "网页搜索结果", "执行器",
            "实际找到了三条网页", at.plusSeconds(8), triggerExtraction = false,
            source = "web_search", evidenceKind = EventEvidenceKind.Observation)
        receipt(a, "trace-search-receipt", "trace-search-origin", "web_search",
            "succeeded", true, "搜索已完成", native = "trace-search-native")
        source(b, "trace-search-foreign", "另一人物自己的私聊")
        val mind = JSONObject().put("decisions", JSONArray().put(
            JSONObject().put("causalEvidenceId", "trace-search-receipt")
                .put("causalEvidenceRevision", 1L)
                .put("at", at.plusSeconds(20).toString())
                .put("selected", "publish_moment")
                .put("reason", "搜索后想整理内容再分享")
                .put("outcome", "尚未收到发布回执")))
        val mixed = SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b)
        val origin = CharacterCausalTrace.inspect(context, a, event(a, "trace-search-origin"),
            mixed, mind)
        val ownReceipt = CharacterCausalTrace.inspect(context, a, event(a, "trace-search-receipt"),
            mixed, mind)
        for (lines in listOf(origin, ownReceipt)) {
            assertTrue(lines.any { it.title == "原生结果记录" &&
                it.evidenceId == "trace-search-native" })
            assertTrue(lines.any { it.title.contains("回执后的本人选择") &&
                it.detail.contains("尚未收到发布回执") })
            assertFalse(lines.any { it.detail.contains("另一人物自己的私聊") })
            assertFalse(lines.any { it.title.contains("目标已完成") })
        }
        assertFalse(PerceptionStimulusLedger.peekSeen(context, a, "trace-search-receipt"))
        // Reading the diagnostic cannot silently mark the tool result perceived.
        CharacterCausalTrace.inspect(context, a, event(a, "trace-search-origin"), mixed, mind)
        assertFalse(PerceptionStimulusLedger.peekSeen(context, a, "trace-search-receipt"))
        source(a, "trace-search-origin", "修订后的另一个问题")
        val revised = CharacterCausalTrace.inspect(context, a, event(a, "trace-search-origin"),
            SharedExperienceTimeline.all(a), mind)
        assertTrue(revised.any { it.title.contains("历史版本的行动回执") })
        assertFalse(revised.any { it.title.contains("回执后的本人选择") })
        assertFalse(revised.any { it.title == "原生结果记录" })
    }

    @Test fun postToolDecisionRequiresActualResultChronologyAndRevision() {
        source(a, "trace-order-origin")
        receipt(a, "trace-order-result", "trace-order-origin",
            "web_search", "succeeded", true, "真实结果已返回")
        // A late persistence/import can attach the correct evidence ID to a
        // decision timestamped before the result. It is not a follow-up.
        CharacterInnerLifeStore.recordDecision(a, "trace-before-result",
            "publish_moment", "结果返回之前已经想分享", "", null,
            "尚未执行", false, now = at.plusSeconds(5),
            causalEvidenceId = "trace-order-result")
        val before = CharacterCausalTrace.inspect(context, a,
            event(a, "trace-order-origin"), SharedExperienceTimeline.all(a), JSONObject())
        assertFalse(before.any { it.title.contains("回执后的本人选择") })
        assertTrue(before.any { it.title.contains("后续关系未证实") &&
            it.detail.contains("早于实际结果") })
        assertFalse(PerceptionStimulusLedger.peekSeen(context, a, "trace-order-result"))

        CharacterInnerLifeStore.recordDecision(a, "trace-after-result",
            "publish_moment", "读完结果后才决定分享", "", null,
            "仍未发布", false, now = at.plusSeconds(20),
            causalEvidenceId = "trace-order-result")
        for (anchor in listOf("trace-order-origin", "trace-order-result")) {
            val lines = CharacterCausalTrace.inspect(context, a, event(a, anchor),
                SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b), JSONObject())
            assertEquals(1, lines.count { it.title.contains("回执后的本人选择") })
            assertTrue(lines.any { it.title.contains("回执后的本人选择") &&
                it.detail.contains("读完结果后才决定分享") })
            assertTrue(lines.any { it.title.contains("后续关系未证实") &&
                it.detail.contains("结果返回之前") })
            assertFalse(lines.any { it.title.contains("执行器确认成功") &&
                it.detail.contains("仍未发布") })
        }
        assertTrue(CharacterCausalTrace.inspect(context, b,
            event(a, "trace-order-origin"), SharedExperienceTimeline.all(a),
            JSONObject()).isEmpty())
    }

    @Test fun editedResultAndLegacyChoiceNeverBecomeVerifiedFollowUp() {
        source(a, "trace-revised-origin")
        receipt(a, "trace-revised-result", "trace-revised-origin",
            "web_search", "succeeded", true, "原来的搜索结果")
        CharacterInnerLifeStore.recordDecision(a, "trace-revised-choice",
            "publish_moment", "针对原来结果的选择", "", null,
            "未发布", false, now = at.plusSeconds(20),
            causalEvidenceId = "trace-revised-result")
        receipt(a, "trace-revised-result", "trace-revised-origin",
            "web_search", "succeeded", true, "修改后的搜索结果")
        val legacy = JSONObject().put("decisions", JSONArray().put(
            JSONObject().put("id", "trace-legacy-choice")
                .put("causalEvidenceId", "trace-revised-result")
                .put("selected", "publish_moment")
                .put("reason", "旧记录没有时间和版本")))
        val lines = CharacterCausalTrace.inspect(context, a,
            event(a, "trace-revised-result"), SharedExperienceTimeline.all(a), legacy)
        assertFalse(lines.any { it.title.contains("回执后的本人选择") })
        assertTrue(lines.any { it.title.contains("后续关系未证实") &&
            it.detail.contains("历史版本") })
        assertTrue(lines.any { it.title.contains("后续关系未证实") &&
            it.detail.contains("旧选择未记录依据版本") })
        SharedExperienceTimeline.deleteEvent("trace-revised-result")
        assertTrue(CharacterCausalTrace.inspect(context, a,
            event(a, "trace-revised-result"), SharedExperienceTimeline.all(a),
            legacy).isEmpty())
    }

    @Test fun perceivedReceiptWithoutFollowUpIsNotMisreportedAsDeliberateSilence() {
        source(a, "trace-perceived-source")
        receipt(a, "trace-perceived-receipt", "trace-perceived-source",
            "web_search", "succeeded", true, "搜索完成")
        assertTrue(PerceptionStimulusLedger.claim(context, a,
            CharacterPerceptionContext.stimulus(event(a, "trace-perceived-receipt"))))
        val lines = CharacterCausalTrace.inspect(context, a, event(a, "trace-perceived-source"),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(lines.any { it.title == "回执后的后续" &&
            it.detail.contains("不能推断已放弃") })
        assertFalse(lines.any { it.title.contains("本人选择安静") })
        assertFalse(CharacterCausalTrace.inspect(context, b,
            event(a, "trace-perceived-source"), SharedExperienceTimeline.all(a),
            JSONObject()).isNotEmpty())
    }

    @Test fun oldReceiptWithoutSourceRevisionCannotAssertCurrentTextCausedFollowUp() {
        source(a, "trace-legacy-source")
        SharedExperienceTimeline.record("trace-legacy-receipt", a, "实际工具执行", "执行器",
            JSONObject().put("tool", "web_search").put("status", "succeeded")
                .put("causeEvidenceId", "trace-legacy-source")
                .put("result", JSONObject().put("success", true).toString()).toString(),
            at.plusSeconds(10), triggerExtraction = false,
            source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        val mind = JSONObject().put("decisions", JSONArray().put(
            JSONObject().put("causalEvidenceId", "trace-legacy-receipt")
                .put("selected", "publish_moment").put("reason", "准备分享")))
        val lines = CharacterCausalTrace.inspect(context, a, event(a, "trace-legacy-source"),
            SharedExperienceTimeline.all(a), mind)
        assertTrue(lines.any { it.title.contains("来源版本未证实") })
        assertFalse(lines.any { it.title.contains("回执后的本人选择") })
    }

    @Test fun searchFinalReplyIsVisibleFromOriginalRequestAndOnlyFromLiveResultRevision() = runBlocking {
        val origin = "trace-feedback-original"
        val searchRequest = "trace-feedback-request"
        val searchReceipt = "tool-$a-$searchRequest"
        source(a, origin, "先搜资料，再告诉我结果")
        receipt(a, searchReceipt, origin, "web_search", "succeeded", true, "找到真实网页")
        assertEquals(searchReceipt, SearchReceiptFeedback.recordedReceiptId(a, searchRequest))
        assertEquals("", SearchReceiptFeedback.recordedReceiptId(b, searchRequest))

        // ToolRouter's real pre-execution refusal must also preserve a
        // terminal, role-owned search receipt as its cause.
        ToolRouter.execute(context, a, "nonexistent_feedback_tool", JSONObject(),
            requestId = "trace-feedback-denied", causeEvidenceId = searchReceipt)
        val denied = event(a, "tool-rejected-$a-trace-feedback-denied")
        assertEquals(searchReceipt, JSONObject(denied.content).optString("causeEvidenceId"))
        assertEquals(1L, JSONObject(denied.content).optLong("causeEvidenceRevision"))
        ToolRouter.execute(context, b, "nonexistent_feedback_tool", JSONObject(),
            requestId = "trace-feedback-foreign", causeEvidenceId = searchReceipt)
        assertEquals("", JSONObject(event(b, "tool-rejected-$b-trace-feedback-foreign").content)
            .optString("causeEvidenceId"))

        val replyId = "tool-$a-$searchRequest:verified-search-feedback"
        SharedExperienceTimeline.record(replyId, a, "实际工具执行", "执行器",
            JSONObject().put("tool", "send_private_message")
                .put("requestId", "$searchRequest:verified-search-feedback")
                .put("causeEvidenceId", searchReceipt)
                .put("causeEvidenceRevision", 1L)
                .put("status", "succeeded")
                .put("result", JSONObject().put("success", true)
                    .put("summary", "结果确实发给了用户").toString()).toString(),
            at.plusSeconds(30), triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
        val original = CharacterCausalTrace.inspect(context, a, event(a, origin),
            SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b), JSONObject())
        assertTrue(original.any { it.evidenceId == replyId &&
            it.title.contains("搜索结果后的实际反馈") && it.title.contains("执行器确认成功") })
        assertTrue(CharacterCausalTrace.inspect(context, a, event(a, searchReceipt),
            SharedExperienceTimeline.all(a), JSONObject()).any { it.evidenceId == replyId })
        assertFalse(CharacterCausalTrace.inspect(context, b, event(b,
            "tool-rejected-$b-trace-feedback-foreign"),
            SharedExperienceTimeline.all(a) + SharedExperienceTimeline.all(b), JSONObject())
            .any { it.evidenceId == replyId })

        // Editing the search result breaks the old source revision. An old
        // delivered message must never be attributed to the revised result.
        receipt(a, searchReceipt, origin, "web_search", "succeeded", true, "编辑后的结果")
        val revised = CharacterCausalTrace.inspect(context, a, event(a, origin),
            SharedExperienceTimeline.all(a), JSONObject())
        assertFalse(revised.any { it.evidenceId == replyId &&
            it.title.contains("搜索结果后的实际反馈") })
        SharedExperienceTimeline.deleteEvent(replyId)
        assertFalse(CharacterCausalTrace.inspect(context, a, event(a, origin),
            SharedExperienceTimeline.all(a), JSONObject())
            .any { it.evidenceId == replyId && it.title.contains("搜索结果后的实际反馈") })
    }


    @Test fun archivedThoughtCanExplainOldUnsharedImpulseWithoutInventingDelivery() {
        val originalId = "trace-archived-impulse"
        source(a, originalId, "看到好看的花，想分享")
        CharacterInnerLifeStore.observe(a, originalId, "看到好看的花，想分享",
            JSONObject().put("thoughts", JSONArray().put(
                JSONObject().put("thought", "想发一条花的动态，但还没决定"))), now = at)
        repeat(90) { index ->
            CharacterInnerLifeStore.observe(a, "trace-impulse-filler-$index",
                "其他独立刺激 $index", JSONObject().put("thoughts", JSONArray().put(
                    JSONObject().put("thought", "其他想法 $index"))),
                now = at.plusSeconds(index + 1L))
        }
        assertTrue((0 until (CharacterInnerLifeStore.snapshot(a)
            .optJSONArray("thoughts")?.length() ?: 0)).none { index ->
            CharacterInnerLifeStore.snapshot(a).optJSONArray("thoughts")
                ?.optJSONObject(index)?.optString("thought") == "想发一条花的动态，但还没决定"
        })
        assertEquals(1, CharacterInnerLifeStore.thoughtsForEvidence(a, originalId).size)
        assertTrue(CharacterInnerLifeStore.thoughtsForEvidence(b, originalId).isEmpty())
        val original = CharacterCausalTrace.inspect(context, a, event(a, originalId),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(original.any { it.title.contains("本人想法") &&
            it.detail.contains("想发一条花的动态") })
        assertFalse(original.any { it.title.contains("执行器确认成功") })
        assertTrue(original.any { it.detail.contains("没有精确关联的决定或执行回执") })
        source(a, originalId, "编辑后决定不分享")
        val edited = CharacterCausalTrace.inspect(context, a, event(a, originalId),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(edited.any { it.title.contains("本人想法") &&
            it.title.contains("历史版本 1") &&
            it.detail.contains("不能用当前文字解释旧想法") })
        assertTrue(CharacterInnerLifeStore.forgetExactThought(a, "想发一条花的动态，但还没决定"))
        assertTrue(CharacterInnerLifeStore.thoughtsForEvidence(a, originalId).isEmpty())
        assertFalse(CharacterCausalTrace.inspect(context, a, event(a, originalId),
            SharedExperienceTimeline.all(a), JSONObject()).any { it.title.contains("本人想法") })
    }

    @Test fun editedSourceRetiresDecisionsAndThoughtsWithoutReplayingOrErasingOtherPeople() {
        val id = "trace-edited-private-history"
        source(a, id, "原话：我可能会分享")
        CharacterInnerLifeStore.observe(a, id, "原话：我可能会分享",
            JSONObject().put("thoughts", JSONArray().put(
                JSONObject().put("thought", "先想一想再分享"))), now = at)
        CharacterInnerLifeStore.recordDecision(a, "trace-old-private-choice",
            "publish_moment", "本人原先选择分享", "", null,
            "没有实际执行回执", false, now = at.plusSeconds(1), causalEvidenceId = id)
        assertEquals(1, CharacterInnerLifeStore.decisionsForEvidence(a, id).size)
        source(a, id, "新话：现在不要分享")
        assertTrue((CharacterInnerLifeStore.snapshot(a).optJSONArray("thoughts")?.length() ?: 0) == 0)
        assertTrue((CharacterInnerLifeStore.snapshot(a).optJSONArray("decisions")?.length() ?: 0) == 0)
        assertEquals(1, CharacterInnerLifeStore.thoughtsForEvidence(a, id).size)
        assertEquals(1, CharacterInnerLifeStore.decisionsForEvidence(a, id).size)
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(b, id).isEmpty())
        val old = CharacterCausalTrace.inspect(context, a, event(a, id),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(old.any { it.title.contains("本人选择") &&
            it.title.contains("历史版本 1") &&
            it.detail.contains("不能用新文字解释旧选择") })
        assertFalse(old.any { it.title.contains("执行器确认成功") })
        SharedExperienceTimeline.deleteEvent(id)
        assertTrue(CharacterInnerLifeStore.thoughtsForEvidence(a, id).isEmpty())
        assertTrue(CharacterInnerLifeStore.decisionsForEvidence(a, id).isEmpty())
        assertTrue(SharedExperienceTimeline.eventsByIds(a, listOf(id)).isEmpty())
    }

    @Test fun repeatedThoughtKeepsEachActualStimulusVersionWithoutClaimingAnAction() {
        source(a, "trace-thought-first", "第一次看到花")
        source(a, "trace-thought-second", "第二次看到花")
        val proposal = JSONObject().put("thoughts", JSONArray().put(
            JSONObject().put("thought", "可能想分享一张照片")))
        CharacterInnerLifeStore.observe(a, "trace-thought-first", "第一次看到花", proposal, now = at)
        CharacterInnerLifeStore.observe(a, "trace-thought-second", "第二次看到花",
            proposal, now = at.plusSeconds(10))
        val linked = CharacterInnerLifeStore.thoughtsForEvidence(a, "trace-thought-second")
        assertEquals(1, linked.size)
        assertEquals(1L, linked.single().optJSONObject("stimulusRevisions")
            ?.optLong("trace-thought-second"))
        val before = CharacterCausalTrace.inspect(context, a, event(a, "trace-thought-second"),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(before.any { it.title.contains("本人想法") &&
            !it.title.contains("来源版本未证实") })
        source(a, "trace-thought-second", "后来修改了这次记录")
        val after = CharacterCausalTrace.inspect(context, a, event(a, "trace-thought-second"),
            SharedExperienceTimeline.all(a), JSONObject())
        assertTrue(after.any { it.title.contains("本人想法") &&
            it.title.contains("历史版本 1") })
        assertTrue(CharacterCausalTrace.inspect(context, b, event(a, "trace-thought-first"),
            SharedExperienceTimeline.all(a), JSONObject()).isEmpty())
    }
}
