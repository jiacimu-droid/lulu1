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
class CharacterExperienceLearningTest {
    private lateinit var context: Context
    private lateinit var role: String
    private val now = Instant.parse("2026-10-10T10:00:00Z")

    @Before fun start() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        role = MigratedDomainStores.characters.create("日常经验测试").characterId
        CharacterLearningLedger.initialize(context)
        user("wake", "午睡刚醒，还是有些困，放首歌吧", 0)
    }

    @After fun finish() { MemoryTestEnvironment.release() }

    private fun user(id: String, text: String, seconds: Long, owner: String = role) {
        SharedExperienceTimeline.record(id, owner, "私聊", "用户", text, now.plusSeconds(seconds),
            triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
    }

    private fun tool(id: String, status: String = "succeeded", success: Boolean = true,
        seconds: Long = 10, cause: String = "wake", tool: String = "control_music",
        application: JSONObject? = null): SharedTimelineEvent {
        val body = JSONObject().put("tool", tool).put("status", status).put("causeEvidenceId", cause)
            .put("reason", "尝试帮助她醒后清醒一点").put("learningApplication", application)
            .put("result", JSONObject().put("success", success)
                .put("summary", if (success) "播放器实际开始播放" else "没有确认播放变化").toString())
        SharedExperienceTimeline.record(id, role, "实际工具执行", "执行器", body.toString(),
            now.plusSeconds(seconds), triggerExtraction = false, source = "tool-router",
            evidenceKind = EventEvidenceKind.ToolResult)
        return CharacterActionEvidence.resolve(role, id)!!
    }

    private fun experience(action: String = "music") = JSONObject()
        .put("topic", "醒后音乐").put("situationEvidenceId", "wake").put("actionEvidenceId", action)
        .put("understanding", "午睡醒来仍困时，可以考虑用她愿意听的音乐帮助过渡")
        .put("applicableWhen", "午睡醒来仍困，需要清醒时")
        .put("avoidWhen", "仍想睡、正在安静学习或明确不想听歌时不使用")
        .put("uncertainty", "一次请求不代表每次都需要，也还不知道清醒效果")

    private fun apply(from: String) = JSONObject().put("sourceEvidenceId", from)
        .put("howApplied", "结合这次实际情境尝试复用醒后音乐经验")

    @Test fun everydayToolExperienceNeedsNoArticleAndSurvivesRestart() {
        tool("music", status = "verified")
        assertTrue(CharacterLearningLedger.recordExperience(role, experience()))
        assertFalse(CharacterLearningLedger.recordExperience(role, experience()))
        assertEquals(1, CharacterLearningLedger.listInsights(role).size)
        val text = CharacterRuntime.learningContext(role, "午睡醒来仍困")
        assertTrue(text.contains("日常经验ID=music"))
        assertTrue(text.contains("效果未知") || text.contains("是否有效未知"))
        assertTrue(text.contains("不适用/停止条件"))
        assertFalse(CharacterLearningLedger.isMaterialEvidence(role, "music"))
        CharacterLearningLedger.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterLearningLedger, null)
        CharacterLearningLedger.initialize(context)
        assertTrue(CharacterLearningLedger.hasInsight(role, "music"))
        val row = CharacterRecordProjection.remembered(role, emptyList(),
            CharacterLearningLedger.listInsights(role), emptyList()).single()
        assertTrue(row.insight!!.fromExperience)
        assertEquals(setOf("wake", "music"), row.sourceIds.toSet())
    }

    @Test fun failedOrUnverifiedAttemptCanTeachLimitsWithoutBecomingSuccess() {
        tool("failed", "failed", false)
        assertTrue(CharacterLearningLedger.recordExperience(role, experience("failed")))
        assertFalse(CharacterActionEvidence.supports(CharacterActionEvidence.resolve(role, "failed")!!, "tool"))
        tool("not-yet-observed", "requested_unverified", false, seconds = 11)
        assertTrue(CharacterLearningLedger.recordExperience(role, experience("not-yet-observed")))
        assertTrue(CharacterLearningLedger.context(role, "醒后音乐").contains("requested_unverified"))
        assertFalse(CharacterLearningLedger.context(role, "醒后音乐").contains("多个独立行动获正面反馈"))
    }

    @Test fun pendingRequestsAndModelClaimsCannotBecomeExperienceOrApplication() {
        tool("music")
        assertTrue(CharacterLearningLedger.recordExperience(role, experience()))
        listOf("pending", "requested", "running", "waiting_permission", "waiting_user").forEach { state ->
            val e = tool("pending-$state", state)
            assertFalse(CharacterLearningLedger.recordExperience(role, experience(e.id)))
            assertFalse(CharacterLearningLedger.recordApplication(role, apply("music"), e.id,
                "capture_screen", true, "模型声称看过了"))
        }
        SharedExperienceTimeline.record("claim", role, "私聊", "角色", "音乐已经放了",
            now.plusSeconds(12), triggerExtraction = false, source = "message",
            evidenceKind = EventEvidenceKind.CharacterStatement)
        assertFalse(CharacterLearningLedger.recordExperience(role, experience("claim")))
        assertFalse(CharacterLearningLedger.recordExperience("another-role", experience()))
    }

    @Test fun attributionRequiresTheRealInputAndVerbatimLaterUserFeedback() {
        tool("music")
        user("other-input", "查一下天气", 1)
        assertFalse(CharacterLearningLedger.recordExperience(role,
            experience().put("situationEvidenceId", "other-input")))
        user("helpful", "听了一会儿，现在清醒多啦", 20)
        val note = experience().put("feedbackEvidenceId", "helpful")
            .put("feedbackQuote", "现在清醒多啦")
        assertFalse(CharacterLearningLedger.recordExperience(role,
            JSONObject(note.toString()).put("feedbackQuote", "以后每天都给我放歌")))
        CharacterLearningLedger.observeInteraction(role, note, null, null, listOf("other-input"))
        assertFalse(CharacterLearningLedger.hasInsight(role, "music"))
        CharacterLearningLedger.observeInteraction(role, note, null, null, listOf("helpful"))
        assertTrue(CharacterLearningLedger.context(role, "午睡醒来").contains("现在清醒多啦"))
        SharedExperienceTimeline.deleteEvent("helpful")
        assertFalse(CharacterLearningLedger.hasInsight(role, "music"))
    }

    @Test fun originalNeedCanPrecedeTheLaterExplicitToolRequestInTheSameConversation() {
        user("please-search", "那你查一下嘛", 4)
        tool("searched", seconds = 10, cause = "please-search", tool = "web_search")
        assertTrue(CharacterLearningLedger.recordExperience(role,
            experience("searched").put("understanding", "原先需求需要资料核实，下次可以主动考虑查证")))
        assertTrue(CharacterLearningLedger.listInsights(role).single().sourceIds.contains("please-search"))
    }

    @Test fun editingSourcesWithdrawsExperienceAndNewCounterEvidenceCanReviseIt() {
        tool("music")
        assertTrue(CharacterLearningLedger.recordExperience(role, experience()))
        tool("music-later", seconds = 30)
        user("objection", "这次别放歌，会打断我学习", 40)
        val revised = experience("music-later").put("revisesEvidenceId", "music")
            .put("understanding", "醒后是否放歌要看是否已经开始学习，不能照搬上次")
            .put("feedbackEvidenceId", "objection").put("feedbackQuote", "会打断我学习")
        assertTrue(CharacterLearningLedger.recordExperience(role, revised))
        assertFalse(CharacterLearningLedger.context(role, "午睡醒来").contains("日常经验ID=music；"))
        assertTrue(CharacterLearningLedger.context(role, "午睡醒来").contains("不能照搬上次"))
        SharedExperienceTimeline.deleteEvent("music-later")
        assertTrue(CharacterLearningLedger.hasInsight(role, "music"))
        user("wake", "原始需求已改写", 0)
        assertFalse(CharacterLearningLedger.hasInsight(role, "music"))
    }

    @Test fun onlineFeedbackUsesTheSameLedgerAsPhoneAndCorrectsTheMethod() {
        tool("music")
        CharacterLearningLedger.recordExperience(role, experience())
        val applied = tool("again", seconds = 25)
        assertFalse(CharacterLearningLedger.recordApplication(role, apply("music").put("action", "capture_screen"),
            applied.id, "control_music", true, "不能把音乐成功归功于另一个动作"))
        assertTrue(CharacterLearningLedger.recordApplication(role, apply("music"), applied.id,
            "control_music", true, "真实播放"))
        user("feedback", "今天请让我安静写题", 35)
        val feedback = JSONObject().put("sourceEvidenceId", "music").put("actionEvidenceId", "again")
            .put("evaluation", "unhelpful").put("quote", "请让我安静写题")
        CharacterLearningLedger.observeInteraction(role, null, feedback, null, listOf("feedback"))
        assertTrue(CharacterLearningLedger.context(role, "午睡醒来").contains("下次需调整或放下"))
        SharedExperienceTimeline.deleteEvent("again")
        assertFalse(CharacterLearningLedger.context(role, "午睡醒来").contains("用户真实反馈ID="))
    }

    @Test fun eachOrderedToolKeepsItsOwnApplicationAndReplayDoesNotDoubleCount() = runBlocking {
        tool("music")
        CharacterLearningLedger.recordExperience(role, experience())
        context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE).edit()
            .putBoolean("$role:media", true).commit()
        val steps = JSONArray().put(JSONObject().put("tool", "control_volume")
            .put("args", JSONObject().put("action", "status"))
            .put("learningApplication", apply("music")))
        val result = OrderedCharacterActions.execute(context, role, steps, "apply-one",
            userEvidenceIds = setOf("wake"))
        assertTrue(result.notes.toString(), result.anyAccepted)
        OrderedCharacterActions.execute(context, role, steps, "apply-one", userEvidenceIds = setOf("wake"))
        val attempts = CharacterLearningLedger.growthTrace(role).filter { it.label == "实际尝试运用所学" }
        assertEquals(1, attempts.size)
        assertEquals("tool-$role-apply-one:step:0", attempts.single().evidenceId)
    }

    @Test fun approvedScreenUsesActualObservationRatherThanEarlierPermissionRequest() {
        tool("music")
        CharacterLearningLedger.recordExperience(role, experience())
        val requestId = "screen-test"
        val pendingId = "tool-$role-$requestId"
        tool(pendingId, "pending", true, 20, tool = "capture_screen", application = apply("music"))
        val observationId = "screen-observation-$role-$requestId"
        SharedExperienceTimeline.record(observationId, role, "屏幕观察", "手机",
            JSONObject().put("success", true).put("status", "observed")
                .put("summary", "真实识别到学习页面").toString(), now.plusSeconds(30),
            triggerExtraction = false, source = "screen-consent", taskId = requestId,
            evidenceKind = EventEvidenceKind.Observation)
        ToolRouter.projectDeferredScreenLearning(role, requestId, observationId)
        val attempts = CharacterLearningLedger.growthTrace(role).filter { it.label == "实际尝试运用所学" }
        assertEquals(listOf(observationId), attempts.map { it.evidenceId })
        assertTrue(CharacterLearningLedger.recordExperience(role, experience(observationId)))
        SharedExperienceTimeline.deleteEvent(pendingId)
        assertFalse(CharacterLearningLedger.hasInsight(role, observationId))
        assertFalse(CharacterLearningLedger.growthTrace(role).any { it.label == "实际尝试运用所学" })
    }

    @Test fun reflectionKeepsFailedToolWithItsOriginalSceneAndOnlineWireKeepsFeedback() {
        tool("failed", "failed", false)
        user("later", "那就先不放了", 25)
        val selected = CharacterExperienceLearning.episodeEvidence(SharedExperienceTimeline.all(role))
        assertTrue(selected.map { it.id }.containsAll(listOf("wake", "failed", "later")))
        val raw = JSONObject().put("action", "message").put("text", "好，今天先安静")
            .put("experienceLearning", experience("failed"))
            .put("learningFeedback", JSONObject().put("actionEvidenceId", "failed"))
            .put("learningChallenge", JSONObject().put("issue", "本次不适用"))
        val parse = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "parseDecision" && it.parameterCount == 2
        }.apply { isAccessible = true }
        val decision = parse.invoke(ProactivePerceptionRuntime, raw.toString(), false)!!
        listOf("experienceLearning", "learningFeedback", "learningChallenge").forEach { field ->
            assertNotNull(decision.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(decision))
        }
    }
}
