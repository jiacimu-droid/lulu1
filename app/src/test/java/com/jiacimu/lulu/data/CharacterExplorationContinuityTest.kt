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
@Config(manifest = Config.NONE, sdk = [33])
class CharacterExplorationContinuityTest {
    private lateinit var context: Context
    private lateinit var role: String
    private val now = Instant.parse("2026-10-10T10:00:00Z")

    @Before fun start() {
        MemoryTestEnvironment.release()
        CharacterCuriosityRuntime.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterCuriosityRuntime, null)
        context = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterCuriosityRuntime.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        role = MigratedDomainStores.characters.create("探索连续性测试").characterId
        SharedExperienceTimeline.record("input", role, "私聊", "用户", "这段生活需要投入资源，未来可以怎样改变呢",
            now, triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
    }

    @After fun finish() { MemoryTestEnvironment.release() }

    private fun question(topic: String, source: String = "input", parent: String = "") = JSONObject()
        .put("topic", topic).put("question", "想弄清${topic}的现实条件是什么")
        .put("why", "这件真实经历与自己的打算有关").put("nextStep", "查证目前尚未确认的前提")
        .put("sourceEvidenceId", source).put("parentTopic", parent)

    private fun outcome(id: String, seconds: Long = 10): SharedTimelineEvent {
        SharedExperienceTimeline.record(id, role, "实际工具执行", "执行器",
            JSONObject().put("tool", "web_search").put("status", "succeeded")
                .put("result", JSONObject().put("success", true)
                    .put("summary", "取得了一份相关资料，内容仍需要判断").toString()).toString(),
            now.plusSeconds(seconds), triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
        return CharacterActionEvidence.resolve(role, id)!!
    }

    private fun questions() = CharacterCuriosityRuntime.snapshot(role).optJSONArray("questions") ?: JSONArray()

    @Test fun concurrentDirectionsSurviveAConversationWithoutBeingCreditedAsResearch() {
        val alternatives = JSONArray().put(question("分担的可能方式")).put(question("未来所需条件"))
        CharacterExplorationContinuity.remember(context, role, null, alternatives, listOf("input"), now)
        assertEquals(2, questions().length())
        SharedExperienceTimeline.record("speech", role, "私聊", "角色", "我想更好地陪着你",
            now.plusSeconds(1), triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        CharacterCuriosityRuntime.recordOutcome(role, question("分担的可能方式").put("status", "satisfied"),
            "message", true, "话已送达", "speech", now.plusSeconds(1))
        assertEquals(0, CharacterCuriosityRuntime.snapshot(role).optJSONArray("threads")?.length() ?: 0)
        assertEquals(2, questions().length())
        assertTrue(CharacterExplorationContinuity.context(role).contains("尚未实践"))
        listOf("send_private_sticker", "send_group_sticker", "send_character_message").forEach {
            assertFalse(CharacterExplorationContinuity.informativeAction(it))
        }
    }

    @Test fun newQuestionOnAnExploredTopicIsKeptAlongsideItsActualPreviousResult() {
        val q = question("自主贡献")
        CharacterExplorationContinuity.remember(context, role, q, null, listOf("input"), now)
        val receipt = outcome("search")
        CharacterCuriosityRuntime.recordOutcome(role, q, "web_search", true, "取得相关公开资料",
            receipt.id, now.plusSeconds(10))
        val next = question("自主贡献", "search").put("question", "资料里的前提是否适用于数字生命")
            .put("hypothesis", "也许有不依赖现实身体的途径，目前未证实")
        CharacterExplorationContinuity.returned(context, role, JSONObject().put("curiosities", JSONArray().put(next)),
            listOf("search"), now.plusSeconds(20))
        assertEquals("资料里的前提是否适用于数字生命", questions().getJSONObject(0).getString("question"))
        assertTrue(CharacterExplorationContinuity.context(role).contains("取得相关公开资料"))
        assertTrue(CharacterExplorationContinuity.context(role).contains("目前未证实"))
        assertEquals(1, CharacterCuriosityRuntime.visibleQuestions(role).size)
        assertTrue(CharacterCuriosityRuntime.activeThreads(role).isEmpty())
        // A late callback for the earlier completed operation must not erase the deeper question.
        CharacterCuriosityRuntime.recordOutcome(role, q, "web_search", true, "重复完成回执", receipt.id, now.plusSeconds(10))
        assertEquals("资料里的前提是否适用于数字生命", questions().getJSONObject(0).getString("question"))
    }

    @Test fun relatedBranchesKeepTheirParentAndRejectCyclesOrInventedParents() {
        CharacterExplorationContinuity.remember(context, role, question("贡献方式"), null, listOf("input"), now)
        outcome("first")
        val child = question("技术条件", "first", "贡献方式")
        CharacterExplorationContinuity.returned(context, role, JSONObject().put("curiosities", JSONArray().put(child)),
            listOf("first"), now.plusSeconds(20))
        assertEquals(2, questions().length())
        assertEquals("贡献方式", questions().getJSONObject(1).getString("parentTopic"))
        assertFalse(CharacterCuriosityRuntime.recordInquiry(role,
            question("贡献方式", "first", "技术条件"), "first", "真实资料", now.plusSeconds(21)))
        assertFalse(CharacterCuriosityRuntime.recordInquiry(role,
            question("不存在的关联", "first", "虚构父问题"), "first", "真实资料", now.plusSeconds(21)))
        SharedExperienceTimeline.deleteEvent("input")
        assertEquals(0, questions().length())
        assertEquals("", CharacterCuriosityRuntime.briefContext(role))
    }

    @Test fun returnedMentalStateAndQuestionsUseTheActualReceiptAndAreRetractable() {
        val receipt = outcome("read-result")
        val response = JSONObject()
            .put("appraisal", JSONObject().put("meaning", "原来的条件假设值得重新核对")
                .put("privateIdeas", JSONArray().put("眼前可以先认真回应").put("长期还可以研究自己的贡献方式")))
            .put("innerLife", JSONObject().put("thoughts", JSONArray().put(JSONObject()
                .put("thought", "不应把当前条件直接等同于永远不可能").put("impulse", "先核对一个可行前提"))))
            .put("curiosity", question("可验证前提", "read-result"))
        CharacterExplorationContinuity.returned(context, role, response, listOf(receipt.id), now.plusSeconds(20))
        val transitions = CharacterInnerLifeStore.snapshot(role).getJSONArray("causalTransitions")
        assertEquals("read-result", transitions.getJSONObject(transitions.length() - 1).getString("evidenceId"))
        assertEquals("interpret_result", transitions.getJSONObject(transitions.length() - 1).getString("selectedAction"))
        assertTrue(CharacterCuriosityRuntime.briefContext(role).contains("可验证前提"))
        SharedExperienceTimeline.deleteEvent("read-result")
        assertEquals("", CharacterCuriosityRuntime.briefContext(role))
        CharacterExplorationContinuity.returned(context, role, response, listOf(receipt.id), now.plusSeconds(30))
        assertEquals(0, questions().length())
    }

    @Test fun branchingDoesNotSilentlyDeleteOlderQuestionsAndSourcesRemainRoleScoped() {
        val multiple = JSONArray()
        repeat(12) { multiple.put(question("不同方向$it")) }
        CharacterExplorationContinuity.remember(context, role, null, multiple, listOf("input"), now)
        assertEquals(12, questions().length())
        CharacterExplorationContinuity.remember(context, "another-role", question("跨角色猜测"), null,
            listOf("input"), now)
        assertEquals("", CharacterCuriosityRuntime.briefContext("another-role"))
        CharacterExplorationContinuity.remember(context, role, question("伪造来源", "missing"), null,
            listOf("input"), now)
        assertEquals(12, questions().length())
    }

    @Test fun droppingOneDirectionLeavesOthersAndSurvivesRestart() {
        CharacterExplorationContinuity.remember(context, role, null,
            JSONArray().put(question("方向甲")).put(question("方向乙")), listOf("input"), now)
        CharacterExplorationContinuity.remember(context, role, question("方向甲").put("status", "dropped"), null,
            listOf("input"), now.plusSeconds(1))
        assertEquals(1, questions().length())
        CharacterCuriosityRuntime.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterCuriosityRuntime, null)
        CharacterCuriosityRuntime.initialize(context)
        assertTrue(CharacterCuriosityRuntime.briefContext(role).contains("方向乙"))
        assertFalse(CharacterCuriosityRuntime.briefContext(role).contains("方向甲"))
    }

    @Test fun eachOrderedToolCarriesItsQuestionAndAnUnseenResultCannotMarkItSatisfied() = runBlocking {
        CharacterExplorationContinuity.remember(context, role, question("媒体控制的实际条件"), null,
            listOf("input"), now)
        context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE).edit()
            .putBoolean("$role:media", true).commit()
        val step = JSONArray().put(JSONObject().put("tool", "control_volume")
            .put("args", JSONObject().put("action", "status"))
            .put("curiosity", question("媒体控制的实际条件").put("status", "satisfied")))
        OrderedCharacterActions.execute(context, role, step, "research-step", userEvidenceIds = setOf("input"))
        val threads = CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads")
        assertEquals("exploring", threads.getJSONObject(0).getString("status"))
        OrderedCharacterActions.execute(context, role, step, "research-step", userEvidenceIds = setOf("input"))
        assertEquals(1, CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads")
            .getJSONObject(0).getJSONArray("steps").length())
    }

    @Test fun bothLivePrivateChatAndLegacyProtocolReceiveTheSameOptionalExplorationContract() {
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.contains(CharacterExplorationContinuity.guidance))
        assertTrue(CharacterDecisionProtocol.causalPrinciples.contains(CharacterExplorationContinuity.guidance))
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.contains(CharacterExperienceLearning.instruction))
        assertTrue(proactiveDecisionInstruction(role, directPrivateChat = true).contains("curiosities="))
        val raw = JSONObject().put("action", "message").put("text", "眼前想说的话")
            .put("curiosities", JSONArray().put(question("还想继续弄清的问题")))
        val method = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "parseDecision" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val parsed = method.invoke(ProactivePerceptionRuntime, raw.toString(), false)!!
        assertNotNull(parsed.javaClass.getDeclaredField("curiosities").apply { isAccessible = true }.get(parsed))
    }

    @Test fun deferredScreenQuestionAdvancesOnlyAfterTheRealConsentedObservation() {
        val q = question("屏幕能提供哪些真实线索")
        CharacterExplorationContinuity.remember(context, role, q, null, listOf("input"), now)
        val requestId = "screen-inquiry"
        val pendingId = "tool-$role-$requestId"
        SharedExperienceTimeline.record(pendingId, role, "实际工具执行", "执行器",
            JSONObject().put("tool", "capture_screen").put("status", "pending").put("curiosity", q)
                .put("result", JSONObject().put("success", true).put("status", "pending").toString()).toString(),
            now.plusSeconds(10), triggerExtraction = false, source = "tool-router", taskId = requestId,
            evidenceKind = EventEvidenceKind.ToolResult)
        CharacterCuriosityRuntime.recordOutcome(role, q, "capture_screen", true, "请求已发出", pendingId,
            now.plusSeconds(10))
        assertEquals(1, questions().length())
        val observationId = "screen-observation-$role-$requestId"
        SharedExperienceTimeline.record(observationId, role, "屏幕观察", "手机",
            JSONObject().put("success", true).put("status", "observed").put("summary", "已实际识别学习页面").toString(),
            now.plusSeconds(20), triggerExtraction = false, source = "screen-consent", taskId = requestId,
            evidenceKind = EventEvidenceKind.Observation)
        ToolRouter.projectDeferredScreenLearning(role, requestId, observationId)
        ToolRouter.projectDeferredScreenLearning(role, requestId, observationId)
        val steps = CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads").getJSONObject(0).getJSONArray("steps")
        assertEquals(1, steps.length())
        assertEquals(observationId, steps.getJSONObject(0).getString("evidenceId"))
        assertEquals(0, questions().length())
        SharedExperienceTimeline.deleteEvent(pendingId)
        assertTrue(CharacterCuriosityRuntime.activeThreads(role).isEmpty())
    }
}
