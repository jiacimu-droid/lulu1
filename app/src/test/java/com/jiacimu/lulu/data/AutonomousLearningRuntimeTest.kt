package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
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
class AutonomousLearningRuntimeTest {
    private val role = "autonomous-learning-test"
    private lateinit var context: Context
    private val now = Instant.parse("2026-10-12T12:00:00Z")
    @Before fun before() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLearningAgenda.initialize(context)
        CharacterLearningAgenda.clearCharacter(role)
        CharacterLearningLedger.clearCharacter(role)
        context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
            .edit().putInt("$role:learning_daily_limit", 10).commit()
        SharedExperienceTimeline.record("learning-origin", role, "在线感知", "系统",
            "实际感知到本轮无新消息，可考虑自己的兴趣", now,
            triggerExtraction = false, source = "online-awareness", evidenceKind = EventEvidenceKind.Observation)
    }
    @After fun after() {
        CharacterLearningAgenda.clearCharacter(role)
        CharacterLearningLedger.clearCharacter(role)
        MemoryTestEnvironment.release()
    }
    private fun goal() = CharacterLearningAgenda.start(role, JSONObject()
        .put("topic", "幽默表达").put("question", "如何用短铺垫创造意外反转")
        .put("sourceEvidenceId", "learning-origin"), now)
    private fun args(id: String, op: String) = JSONObject().put("goalId", id).put("op", op)
    private fun material() = LearningMaterial("幽默方法", "https://example.org/material",
        "这是真实传输层提供的测试材料，讨论铺垫、反转与人物语境。".repeat(12), "测试来源正文")
    private val note = """{"learning":{"topic":"幽默表达","understanding":"用短铺垫建立预期，再在真实语境中反转","uncertainty":"实际是否好笑仍需检验","applicableWhen":"适合讲笑话时"},"nextStep":"试写一个短笑话"}"""

    @Test fun chosenStudyActuallyRetrievesMaterialAndFeedsItToUnderstanding() = runBlocking {
        val id = goal().getString("id")
        var fetches = 0; var calls = 0
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> fetches++; material() }, understand = { facts, _ ->
                calls++; assertTrue(facts.contains(material().text)); note
            })
        assertTrue(result.summary, result.success)
        assertEquals(1, fetches); assertEquals(1, calls)
        assertTrue(CharacterLearningLedger.context(role, "讲笑话").contains("来源正文"))
        assertEquals("practice", CharacterLearningAgenda.get(role, id)!!.optString("status"))
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(result.evidenceId)).isNotEmpty())
        assertTrue(CharacterLifeFollowThroughStore.hasOpenOpportunity(role, result.evidenceId))
        SharedExperienceTimeline.deleteEvent("learning-origin")
        assertNull(CharacterLearningAgenda.get(role, id))
        assertFalse(CharacterLifeFollowThroughStore.hasOpenOpportunity(role, result.evidenceId))
        assertEquals("", CharacterLearningLedger.context(role, "幽默"))
    }
    @Test fun newSubjectCanActuallyStudyInOneChosenToolAction() = runBlocking {
        var fetches = 0
        val result = AutonomousLearningRuntime.execute(context, role, JSONObject()
            .put("op", "study").put("topic", "幽默表达")
            .put("question", "如何用短铺垫创造意外反转")
            .put("why", "想更自然地开玩笑")
            .put("sourceEvidenceId", "learning-origin"),
            materialLoader = { _, _ -> fetches++; material() },
            understand = { _, _ -> note })
        assertTrue(result.summary, result.success)
        assertEquals(1, fetches)
        assertEquals(1, CharacterLearningAgenda.goals(role).size)
        val goal = CharacterLearningAgenda.goals(role).single()
        assertEquals("practice", goal.optString("status"))
        assertEquals(result.evidenceId, goal.optString("materialId"))
        assertEquals(1, CharacterLearningAgenda.budget(role).used)
    }

    @Test fun newSubjectWithoutRealOriginCannotCreateAnIllusoryStudy() = runBlocking {
        val result = runCatching {
            AutonomousLearningRuntime.execute(context, role, JSONObject()
                .put("op", "study").put("topic", "幽默表达")
                .put("question", "如何用短铺垫创造意外反转"),
                materialLoader = { _, _ -> error("未授权起因时不能联网") })
        }
        assertTrue(result.isFailure)
        assertTrue(CharacterLearningAgenda.goals(role).isEmpty())
        assertEquals(0, CharacterLearningAgenda.budget(role).used)
    }

    @Test fun alreadyReadMaterialCanBeUnderstoodAfterModelFailureWithoutAnotherFetch() = runBlocking {
        val id = goal().getString("id")
        assertFalse(AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> material() }, understand = { _, _ -> "incomplete" }).success)
        val first = CharacterLearningAgenda.get(role, id)!!
        val source = first.getString("materialId")
        CharacterLearningAgenda.update(role, id) { it.put("retryAt", Instant.EPOCH.toString()) }
        assertTrue(CharacterLearningAgenda.prompt(role).contains("\"op\":\"understand\""))
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "understand"),
            materialLoader = { _, _ -> error("已有正文不能再次下载") },
            understand = { facts, _ -> assertTrue(facts.contains(material().text)); note })
        assertTrue(result.summary, result.success)
        assertEquals(source, result.evidenceId)
        assertTrue(CharacterLearningLedger.hasInsight(role, source))
        assertTrue(CharacterLifeFollowThroughStore.hasOpenOpportunity(role, source))
        SharedExperienceTimeline.deleteEvent(source)
        assertFalse(CharacterLifeFollowThroughStore.hasOpenOpportunity(role, source))
        assertEquals("", CharacterLearningLedger.context(role, "幽默"))
    }

    @Test fun searchCandidatesSurviveRestartButNotSourceDeletion() = runBlocking {
        val id = goal().getString("id")
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "search"),
            searchWeb = { _, _ -> listOf(WebSearchHit("已发现的资料", "https://example.org/discovered", "仅摘要")) },
            understand = { _, _ -> error("搜索不应生成理解") })
        assertTrue(result.success)
        CharacterLearningAgenda::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        CharacterLearningAgenda.initialize(context)
        val restored = CharacterLearningAgenda.get(role, id)!!
        val receipt = SharedExperienceTimeline.eventsByIds(role, listOf(result.evidenceId)).single()
        assertEquals(result.evidenceId, restored.getString("lastSearchId"))
        assertEquals(receipt.revision, restored.getLong("lastSearchRevision"))
        val resumed = CharacterLearningAgenda.prompt(role)
        // Android JSON escapes URL slashes; match the quoted data representation.
        val quotedUrl = JSONObject.quote("https://example.org/discovered")
        assertTrue(resumed, resumed.contains(quotedUrl))
        assertTrue(resumed.contains("不是已读正文"))
        SharedExperienceTimeline.deleteEvent(result.evidenceId)
        val withdrawn = CharacterLearningAgenda.prompt(role)
        assertFalse(withdrawn, withdrawn.contains(quotedUrl))
        assertFalse(CharacterLearningAgenda.get(role, id)!!.has("lastSearchId"))
    }

    @Test fun pausedRecentGoalsCannotHideAnOlderExecutableQuestion() {
        val old = goal().getString("id")
        repeat(5) { index ->
            val g = CharacterLearningAgenda.start(role, JSONObject().put("topic", "新主题$index")
                .put("question", "想要研究这个新问题$index").put("sourceEvidenceId", "learning-origin"), now.plusSeconds(index + 1L))
            CharacterLearningAgenda.update(role, g.getString("id")) { it.put("status", "paused") }
        }
        val prompt = CharacterLearningAgenda.prompt(role, now.plusSeconds(100))
        assertTrue(prompt.contains(old))
        assertTrue(prompt.contains("\"op\":\"study\""))
    }

    @Test fun exhaustedBudgetDoesNotOfferAnotherChargeableStep() {
        val id = goal().getString("id")
        context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
            .edit().putInt("$role:learning_daily_limit", 1).commit()
        CharacterLearningAgenda.begin(context, role, id, now)
        CharacterLearningAgenda.failed(role, id, "真实网络失败", now)
        val prompt = CharacterLearningAgenda.prompt(role, now.plusSeconds(3_600))
        assertTrue(prompt, prompt.contains("今日已用1/1"))
        assertFalse(prompt, prompt.contains("tool=learn_topic"))
        assertEquals(0, CharacterLearningAgenda.budget(role, now).remaining)
    }
    @Test fun webSearchOnlyDiscoversLinksWithoutCreatingKnowledge() = runBlocking {
        val id = goal().getString("id")
        val result = AutonomousLearningRuntime.execute(context, role,
            args(id, "search").put("query", "如何自然聊天"),
            searchWeb = { _, query ->
                assertEquals("如何自然聊天", query)
                listOf(WebSearchHit("实际来源标题", "https://example.org/page", "这只是摘要"))
            },
            understand = { _, _ -> error("搜索不应触发学习模型") })
        assertTrue(result.summary, result.success)
        assertTrue(result.summary.contains("尚未读过正文"))
        assertFalse(CharacterLearningLedger.hasInsight(role,
            CharacterLearningAgenda.get(role, id)!!.optString("lastSearchId")))
        assertEquals("planned", CharacterLearningAgenda.get(role, id)!!.optString("status"))
    }
    @Test fun usefulEmptyUnderstandingKeepsReadReceiptWithoutFalseSkill() = runBlocking {
        val id = goal().getString("id")
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> material() }, understand = { _, _ ->
                """{"nextStep":"暂未看懂，试试换一份材料"}"""
            })
        assertTrue(result.summary, result.success)
        assertEquals("research", CharacterLearningAgenda.get(role, id)!!.optString("status"))
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(result.evidenceId)).isNotEmpty())
        assertFalse(CharacterLearningLedger.hasInsight(role, result.evidenceId))
    }
    @Test fun noMaterialAndNoModelResponseCannotCreateKnowledge() = runBlocking {
        val id = goal().getString("id")
        var calls = 0
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> error("网络不可用") }, understand = { _, _ -> calls++; note })
        assertFalse(result.success); assertEquals(0, calls)
        assertEquals("failed", CharacterLearningAgenda.get(role, id)!!.optString("status"))
        assertEquals("", CharacterLearningLedger.context(role, "幽默"))
    }
    @Test fun failedUnderstandingPreservesActualMaterialButNotPretendInsight() = runBlocking {
        val id = goal().getString("id")
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> material() }, understand = { _, _ -> "格式不对" })
        assertFalse(result.success)
        val materialId = CharacterLearningAgenda.get(role, id)!!.getString("materialId")
        assertTrue(SharedExperienceTimeline.eventsByIds(role, listOf(materialId)).isNotEmpty())
        assertFalse(CharacterLearningLedger.hasInsight(role, materialId))
    }
    @Test fun privateExerciseIsRetrievableWithoutBecomingUserApprovalAndCanBeDeleted() = runBlocking {
        val id = goal().getString("id")
        assertTrue(AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> material() }, understand = { _, _ -> note }).success)
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "practice"),
            materialLoader = { _, _ -> error("练习不应再发起网络请求") }, understand = { _, _ ->
                """{"artifact":"今天的练习笑话草稿","selfAssessment":"反转还不够自然","nextStep":"缩短铺垫"}"""
            })
        assertTrue(result.summary, result.success)
        val text = CharacterLearningLedger.context(role, "幽默")
        assertTrue(text.contains("今天的练习笑话草稿"))
        assertFalse(text.contains("用户真实反馈ID="))
        assertFalse(CharacterActionEvidence.supports(SharedExperienceTimeline.eventsByIds(role,
            listOf(result.evidenceId)).single(), "message"))
        SharedExperienceTimeline.deleteEvent(result.evidenceId)
        assertFalse(CharacterLearningLedger.context(role, "幽默").contains("今天的练习笑话草稿"))
    }
    @Test fun materialDeletedWhileModelWorksWithdrawsLateResult() = runBlocking {
        val id = goal().getString("id")
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "study"),
            materialLoader = { _, _ -> material() }, understand = { _, _ ->
                SharedExperienceTimeline.deleteEvent(CharacterLearningAgenda.get(role, id)!!.getString("materialId"))
                note
            })
        assertFalse(result.success)
        assertEquals("", CharacterLearningLedger.context(role, "幽默"))
    }
    @Test fun budgetPauseAndRestartRecoveryDoNotInventCompletion() {
        val id = goal().getString("id")
        context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
            .edit().putInt("$role:learning_daily_limit", 0).commit()
        assertTrue(runCatching { CharacterLearningAgenda.begin(context, role, id, now) }.isFailure)
        CharacterLearningAgenda.update(role, id) { it.put("status", "paused") }
        assertEquals("paused", CharacterLearningAgenda.get(role, id, now.plusSeconds(1000))!!.getString("status"))
        CharacterLearningAgenda.update(role, id) { it.put("status", "running").put("startedAt", now.toString()) }
        // Reopen the same durable preferences; expired running work is not success.
        CharacterLearningAgenda::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        CharacterLearningAgenda.initialize(context)
        assertEquals("failed", CharacterLearningAgenda.get(role, id, now.plusSeconds(601))!!.getString("status"))
        assertTrue(CharacterLearningAgenda.prompt(role, now.plusSeconds(602)).contains("上次学习中断"))
        assertEquals("", CharacterLearningLedger.context(role, "幽默"))
    }

    @Test fun anActualXhsReadingCanJoinTheSameLearningGoalWithoutSearchingAgain() = runBlocking {
        val id = goal().getString("id")
        val source = "xhs-read-for-learning"
        SharedExperienceTimeline.record(source, role, "小红书阅读", "外部平台",
            JSONObject().put("title", "短铺垫与反转")
                .put("url", "https://www.xiaohongshu.com/explore/1234567890abcdef12345678")
                .put("text", "作者以一个简短铺垫和一个意外结尾讲解表达方法。")
                .put("discoveryMode", "search").toString(), now,
            source = "xiaohongshu-webview", triggerExtraction = false,
            evidenceKind = EventEvidenceKind.Observation)
        val attached = AutonomousLearningRuntime.execute(context, role,
            args(id, "attach").put("sourceEvidenceId", source),
            materialLoader = { _, _ -> error("已有小红书原文不能再次下载") },
            understand = { _, _ -> error("关联材料时不额外调用模型") })
        assertTrue(attached.summary, attached.success)
        assertEquals("research", CharacterLearningAgenda.get(role, id)!!.optString("status"))
        assertFalse(CharacterLearningLedger.hasInsight(role, source))
        val result = AutonomousLearningRuntime.execute(context, role, args(id, "understand"),
            materialLoader = { _, _ -> error("已有小红书原文不能再次下载") },
            understand = { facts, _ -> assertTrue(facts.contains("简短铺垫")); note })
        assertTrue(result.summary, result.success)
        assertEquals(source, result.evidenceId)
        assertTrue(CharacterLearningLedger.hasInsight(role, source))
        assertEquals("practice", CharacterLearningAgenda.get(role, id)!!.optString("status"))
        SharedExperienceTimeline.deleteEvent(source)
        assertFalse(CharacterLearningLedger.hasInsight(role, source))
        assertTrue(CharacterLearningAgenda.get(role, id)!!.optString("materialId").isBlank())
    }
}
