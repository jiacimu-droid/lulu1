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

    @Test fun originalResultDeletionAndSourceWithdrawalNeverReviveOldEvidence() {
        source(a, "trace-origin")
        SharedExperienceTimeline.record("trace-native", a, "朋友圈", "角色", "真正发布的内容",
            at.plusSeconds(2), triggerExtraction = false,
            source = "moment:self", evidenceKind = EventEvidenceKind.CharacterStatement)
        receipt(a, "trace-receipt", "trace-origin", "publish_moment", "succeeded", true,
            "已发布", native = "trace-native")
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
}
