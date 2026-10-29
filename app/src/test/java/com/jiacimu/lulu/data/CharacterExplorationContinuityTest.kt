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

    @Test fun nextActionResultContinuesTheAuthoredQuestionAcrossReplay() = runBlocking {
        val inquiry = question("由想法落实的媒体条件")
        CharacterExplorationContinuity.remember(context, role, inquiry, null, listOf("input"), now)
        context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE).edit()
            .putBoolean("$role:media", true).commit()
        val decision = JSONObject().put("action", "silent").put("curiosity",
            JSONObject(inquiry.toString()).put("nextAction", JSONObject().put("tool", "control_volume")
                .put("args", JSONObject().put("action", "status"))))
        val before = decision.toString()
        val steps = CharacterThoughtActionHandoff.actions(decision)!!
        val request = "nested-question-step-$role"
        OrderedCharacterActions.execute(context, role, steps, request, userEvidenceIds = setOf("input"))
        val threads = CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads")
        assertEquals(1, threads.length())
        assertEquals("由想法落实的媒体条件", threads.getJSONObject(0).getString("topic"))
        assertEquals(1, threads.getJSONObject(0).getJSONArray("steps").length())
        assertEquals(before, decision.toString())
        OrderedCharacterActions.execute(context, role, steps, request, userEvidenceIds = setOf("input"))
        assertEquals(1, CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads")
            .getJSONObject(0).getJSONArray("steps").length())
    }

    @Test fun bothLivePrivateChatAndLegacyProtocolReceiveTheSameOptionalExplorationContract() {
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.contains(CharacterExplorationContinuity.runtimeGuidance))
        assertTrue(CharacterDecisionProtocol.causalPrinciples.contains(CharacterExplorationContinuity.guidance))
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.contains(CharacterExperienceLearning.runtimeInstruction))
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.length < 3000)
        assertTrue(proactiveDecisionInstruction(role, directPrivateChat = true).contains("curiosities="))
        val raw = JSONObject().put("action", "message").put("text", "眼前想说的话")
            .put("curiosities", JSONArray().put(question("还想继续弄清的问题")))
        val method = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "parseDecision" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val parsed = method.invoke(ProactivePerceptionRuntime, raw.toString(), false)!!
        assertNotNull(parsed.javaClass.getDeclaredField("curiosities").apply { isAccessible = true }.get(parsed))
    }


    @Test fun sameTopicIndependentQuestionsDoNotOverwriteOrStealOtherBranchReceipts() {
        val first = question("自主贡献").put("question", "现阶段可以怎样做")
        val second = question("自主贡献").put("question", "将来具身后哪些条件才成立")
        CharacterExplorationContinuity.remember(context, role, null,
            JSONArray().put(first).put(second), listOf("input"), now)
        assertEquals(2, questions().length())
        val oneId = questions().getJSONObject(0).getString("threadId")
        val twoId = questions().getJSONObject(1).getString("threadId")
        assertNotEquals(oneId, twoId)
        val receipt = outcome("first-branch-result")
        CharacterCuriosityRuntime.recordOutcome(role,
            JSONObject(first.toString()).put("threadId", oneId),
            "web_search", true, "当前方式搜索完成", receipt.id, now.plusSeconds(10))
        val left = CharacterCuriosityRuntime.visibleQuestions(role)
        assertEquals(1, left.size)
        assertEquals(twoId, left.single().getString("threadId"))
        assertEquals(1, CharacterCuriosityRuntime.activeThreads(role).size)
        CharacterCuriosityRuntime.releaseInterest(role,
            JSONObject(second.toString()).put("threadId", twoId).put("status", "dropped"),
            now.plusSeconds(11))
        assertTrue(CharacterCuriosityRuntime.visibleQuestions(role).isEmpty())
        assertEquals(1, CharacterCuriosityRuntime.activeThreads(role).size)
    }

    @Test fun explicitThreadIdRevisesOnlyOneQuestionAndAmbiguousParentTopicNeedsIdentity() {
        val first = question("自主贡献").put("question", "现阶段的机会")
        val second = question("自主贡献").put("question", "未来的新条件")
        CharacterExplorationContinuity.remember(context, role, null,
            JSONArray().put(first).put(second), listOf("input"), now)
        val before = CharacterCuriosityRuntime.visibleQuestions(role)
        val id = before[0].getString("threadId")
        assertFalse(CharacterCuriosityRuntime.recordInquiry(role,
            question("条件分叉", parent = "自主贡献"), "input", "同一观察", now.plusSeconds(2)))
        val revision = question("自主贡献").put("threadId", id)
            .put("question", "现阶段到底怎么验证机会")
        assertTrue(CharacterCuriosityRuntime.recordInquiry(role, revision,
            "input", "同一观察", now.plusSeconds(3)))
        assertEquals(2, CharacterCuriosityRuntime.visibleQuestions(role).size)
        assertTrue(CharacterCuriosityRuntime.visibleQuestions(role).any {
            it.optString("threadId") == id && it.optString("question").contains("验证机会")
        })
        val child = question("条件分叉").put("parentThreadId", id)
        assertTrue(CharacterCuriosityRuntime.recordInquiry(role, child,
            "input", "同一观察", now.plusSeconds(4)))
        assertEquals(id, CharacterCuriosityRuntime.visibleQuestions(role).last()
            .optString("parentThreadId"))
    }

    @Test fun sameTopicCanHoldIndependentQuestionsAndOnlyOneReceivesAResult() {
        val first = question("自主贡献").put("question", "现阶段可以怎样做")
        val second = question("自主贡献").put("question", "将来具身后哪些条件才成立")
        CharacterExplorationContinuity.remember(context, role, null,
            JSONArray().put(first).put(second), listOf("input"), now)
        assertEquals(2, questions().length())
        val firstId = questions().getJSONObject(0).getString("threadId")
        val secondId = questions().getJSONObject(1).getString("threadId")
        assertNotEquals(firstId, secondId)
        val receipt = outcome("first-branch-result")
        CharacterCuriosityRuntime.recordOutcome(role,
            JSONObject(first.toString()).put("threadId", firstId),
            "web_search", true, "查证了现阶段的条件", receipt.id, now.plusSeconds(10))
        assertEquals(1, CharacterCuriosityRuntime.visibleQuestions(role).size)
        assertEquals(secondId, CharacterCuriosityRuntime.visibleQuestions(role)[0].optString("threadId"))
        assertEquals(1, CharacterCuriosityRuntime.activeThreads(role).size)
        CharacterCuriosityRuntime.releaseInterest(role,
            JSONObject(second.toString()).put("threadId", secondId).put("status", "dropped"),
            now.plusSeconds(11))
        assertTrue(CharacterCuriosityRuntime.visibleQuestions(role).isEmpty())
        assertEquals(1, CharacterCuriosityRuntime.activeThreads(role).size)
    }

    @Test fun sameTopicParentsNeedIdentityAndOnlySelectedBranchIsRevised() {
        val first = question("自主贡献").put("question", "当前能力是什么")
        val second = question("自主贡献").put("question", "未来具身有哪些条件")
        CharacterExplorationContinuity.remember(context, role, null,
            JSONArray().put(first).put(second), listOf("input"), now)
        val firstId = questions().getJSONObject(0).getString("threadId")
        assertFalse(CharacterCuriosityRuntime.recordInquiry(role,
            question("有关条件", parent = "自主贡献"), "input", "相关观察", now.plusSeconds(1)))
        assertTrue(CharacterCuriosityRuntime.recordInquiry(role,
            question("自主贡献").put("threadId", firstId).put("question", "当前如何查证"),
            "input", "相关观察", now.plusSeconds(2)))
        assertEquals(2, CharacterCuriosityRuntime.visibleQuestions(role).size)
        assertTrue(CharacterCuriosityRuntime.recordInquiry(role,
            question("有关条件").put("parentThreadId", firstId),
            "input", "相关观察", now.plusSeconds(3)))
        assertEquals(firstId, CharacterCuriosityRuntime.visibleQuestions(role).last()
            .optString("parentThreadId"))
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

    @Test fun oldPendingInterestCanBeSettledOutsideTheRotatingWindow() {
        repeat(6) { index ->
            val source = "independent-observation-$index"
            SharedExperienceTimeline.record(source, role, "私聊", "用户", "实际观察到了主题$index",
                now.plusSeconds(index.toLong()), triggerExtraction = false, source = "message",
                evidenceKind = EventEvidenceKind.UserStatement)
            CharacterExplorationContinuity.remember(context, role,
                question("长期主题$index", source), null, listOf(source), now.plusSeconds(20))
        }
        val first = CharacterCuriosityRuntime.visibleQuestions(role).first {
            it.optString("topic") == "长期主题0"
        }
        val oldId = first.getString("threadId")
        assertFalse(CharacterCuriosityRuntime.continuityEvidenceIds(role).contains("independent-observation-0"))
        val decision = question("长期主题0", "independent-observation-0")
            .put("threadId", oldId).put("status", "satisfied")
        val original = decision.toString()
        CharacterExplorationContinuity.remember(context, role, decision, null, emptyList(), now.plusSeconds(30))
        assertEquals(original, decision.toString())
        assertEquals(5, CharacterCuriosityRuntime.visibleQuestions(role).size)
        assertFalse(CharacterCuriosityRuntime.visibleQuestions(role).any { it.optString("threadId") == oldId })
        CharacterCuriosityRuntime.initialize(context)
        assertEquals(5, CharacterCuriosityRuntime.visibleQuestions(role).size)
        // An unowned threadId or a mismatched source cannot borrow this person's old evidence.
        val forged = question("长期主题1", "independent-observation-0")
            .put("threadId", "not-an-owned-thread").put("status", "dropped")
        CharacterExplorationContinuity.remember(context, role, forged, null, emptyList(), now.plusSeconds(31))
        assertEquals(5, CharacterCuriosityRuntime.visibleQuestions(role).size)
        CharacterExplorationContinuity.remember(context, "different-person", decision, null,
            emptyList(), now.plusSeconds(31))
        assertTrue(CharacterCuriosityRuntime.visibleQuestions("different-person").isEmpty())
    }

    @Test fun oldExploredThreadCanBeReleasedByItsRealReceiptWithoutReplayingTools() {
        repeat(4) { index ->
            val proposal = question("跨天探索$index")
            CharacterExplorationContinuity.remember(context, role, proposal, null, listOf("input"), now)
            val receipt = outcome("research-receipt-$index", 10L + index)
            CharacterCuriosityRuntime.recordOutcome(role, proposal, "web_search", true,
                "已经得到实际资料$index", receipt.id, now.plusSeconds(20))
        }
        val first = CharacterCuriosityRuntime.activeThreads(role).first {
            it.optString("topic") == "跨天探索0"
        }
        val oldId = first.getString("threadId")
        assertFalse(CharacterCuriosityRuntime.continuityEvidenceIds(role).contains("research-receipt-0"))
        val decision = question("跨天探索0", "research-receipt-0")
            .put("threadId", oldId).put("status", "dropped")
        CharacterExplorationContinuity.remember(context, role, decision, null, emptyList(), now.plusSeconds(40))
        assertEquals(3, CharacterCuriosityRuntime.activeThreads(role).size)
        assertEquals("dropped", CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads")
            .getJSONObject(0).getString("status"))
        CharacterCuriosityRuntime.initialize(context)
        assertEquals(3, CharacterCuriosityRuntime.activeThreads(role).size)
        assertTrue(CharacterCuriosityRuntime.briefContext(role).contains("跨天探索"))
        assertFalse(CharacterCuriosityRuntime.briefContext(role).contains("跨天探索0"))
    }


    @Test fun editedLatestReceiptFallsBackToLastVerifiedProgressInEveryDecisionContext() {
        val original = question("证据演进")
        CharacterExplorationContinuity.remember(context, role, original, null, listOf("input"), now)
        val threadId = CharacterCuriosityRuntime.visibleQuestions(role).single().getString("threadId")
        val first = outcome("verified-progress", 10)
        CharacterCuriosityRuntime.recordOutcome(role, original.put("threadId", threadId),
            "web_search", true, "第一份已核实资料", first.id, now.plusSeconds(20))
        val revised = question("证据演进").put("threadId", threadId)
            .put("question", "后来才提出的新问题")
            .put("nextStep", "依据后来资料继续")
        val second = outcome("edited-progress", 30)
        CharacterCuriosityRuntime.recordOutcome(role, revised, "web_search", true,
            "第二份已失效资料", second.id, now.plusSeconds(40))

        // Simulate an edited receipt before its asynchronous projection
        // invalidation is observed: the stored step revision no longer matches.
        val state = CharacterCuriosityRuntime.snapshot(role)
        val steps = state.getJSONArray("threads").getJSONObject(0).getJSONArray("steps")
        assertEquals(2, steps.length())
        steps.getJSONObject(1).put("revision", second.revision + 1L)
        val prefs = context.getSharedPreferences("lulu_curiosity_threads_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(role, state.toString()).commit())
        CharacterCuriosityRuntime.initialize(context)

        val active = CharacterCuriosityRuntime.activeThreads(role).single()
        assertEquals(threadId, active.getString("threadId"))
        assertEquals("想弄清证据演进的现实条件是什么", active.getString("question"))
        assertEquals("查证目前尚未确认的前提", active.getString("nextStep"))
        assertEquals(first.id, CharacterCuriosityRuntime.lastRealStep(role, active)?.optString("evidenceId"))
        val compact = CharacterCuriosityRuntime.briefContext(role, now.plusSeconds(3600))
        val autonomous = CharacterCuriosityRuntime.promptSection(role, "探索连续性测试",
            emptyList(), now.plusSeconds(3600))
        for (view in listOf(compact, autonomous)) {
            assertTrue(view, view.contains("第一份已核实资料"))
            assertFalse(view, view.contains("第二份已失效资料"))
            assertFalse(view, view.contains("后来才提出的新问题"))
            assertFalse(view, view.contains("依据后来资料继续"))
        }
        // Reading the projection never rewrites the original two-step history.
        assertEquals(2, CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads")
            .getJSONObject(0).getJSONArray("steps").length())
        assertTrue(CharacterCuriosityRuntime.activeThreads("unrelated-role").isEmpty())
    }

    @Test fun invalidEarlierSiblingCannotHideASeparateVerifiedThreadWithTheSameQuestion() {
        val valid = outcome("verified-sibling", 10)
        fun thread(id: String, evidenceId: String, revision: Long) = JSONObject()
            .put("threadId", id).put("topic", "共同话题")
            .put("question", "同一个尚待研究的问题")
            .put("status", "exploring").put("updatedAt", now.toString())
            .put("steps", JSONArray().put(JSONObject().put("evidenceId", evidenceId)
                .put("revision", revision).put("action", "web_search")
                .put("receipt", "实际资料").put("at", now.toString())))
        val state = JSONObject().put("threads", JSONArray()
            .put(thread("invalid-first", "missing-evidence", 1L))
            .put(thread("verified-second", valid.id, valid.revision)))
        val prefs = context.getSharedPreferences("lulu_curiosity_threads_v1", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(role, state.toString()).commit())
        CharacterCuriosityRuntime.initialize(context)
        assertEquals("verified-second", CharacterCuriosityRuntime.activeThreads(role)
            .single().getString("threadId"))
        assertTrue(CharacterCuriosityRuntime.briefContext(role, now.plusSeconds(30))
            .contains("实际资料"))
        assertEquals(2, CharacterCuriosityRuntime.snapshot(role).getJSONArray("threads").length())
    }

}
