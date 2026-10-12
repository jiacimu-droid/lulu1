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
        SharedExperienceTimeline.deleteEvent("learning-origin")
        assertNull(CharacterLearningAgenda.get(role, id))
        assertEquals("", CharacterLearningLedger.context(role, "幽默"))
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
}
