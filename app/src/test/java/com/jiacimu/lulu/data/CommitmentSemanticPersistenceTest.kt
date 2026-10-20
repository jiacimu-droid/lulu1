package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.LexiconSection
import com.jiacimu.lulu.core.PromiseKind
import com.jiacimu.lulu.system.LuluAlarmSystem
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CommitmentSemanticPersistenceTest {
    private lateinit var context: Context
    private lateinit var role: String
    private val zone = ZoneId.of("Asia/Shanghai")

    @Before fun before() {
        MemoryTestEnvironment.release()
        context = RuntimeEnvironment.getApplication()
        CommitmentTaskStore.snapshot().forEach { CommitmentTaskStore.remove(it.id) }
        resetTaskPreferences()
        context.getSharedPreferences("lulu_commitment_tasks", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("lulu_semantic_commitment_turns", Context.MODE_PRIVATE).edit().clear().commit()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        CommitmentTaskStore.initialize(context)
        LuluAlarmSystem.initialize(context)
        role = MigratedDomainStores.characters.create("语义约定回归角色", "说话直接").characterId
    }

    @After fun after() {
        CommitmentTaskStore.snapshot().forEach { CommitmentTaskStore.remove(it.id) }
        resetTaskPreferences()
        MemoryTestEnvironment.release()
    }

    @Test fun modelInterpretsMidnightAndCorrectionWithoutCalendarOrKeywordShortcuts() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""[{"action":"create","goal":"10月16日09:00 叫醒用户","dueAt":"2026-10-16T01:00:00Z","timezone":"Asia/Shanghai"}]"""))
        server.enqueue(response("""[{"action":"reschedule","targetTaskId":"old-wake","goal":"10月16日09:00 叫醒用户","dueAt":"2026-10-16T01:00:00Z","timezone":"Asia/Shanghai"}]"""))
        server.start()
        try {
            configure(server)
            val now = Instant.parse("2026-10-15T16:10:00Z")
            val drafts = extractCommitmentTaskDrafts(role, "明天9点叫我起床", "好", emptyList(),
                recentContext = "用户：我现在准备睡了", now = now, zone = zone)
            assertEquals(Instant.parse("2026-10-16T01:00:00Z"), drafts.single().dueAt)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(request.contains("2026-10-16T00:10"))
            assertTrue(request.contains("我现在准备睡了"))
            val old = CommitmentTask(id = "old-wake", characterId = role, goal = "17号叫醒",
                dueAt = Instant.parse("2026-10-17T01:00:00Z"))
            val corrected = extractCommitmentTaskDrafts(role, "不是17号呀，是16号呀", "对对对", listOf(old), now = now, zone = zone)
            assertEquals("old-wake", corrected.single().targetTaskId)
            assertTrue(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8().contains("不是17号呀"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun acceptedTwentyMinuteWakeCreatesNativeAlarmAfterEmptyModelCommitmentDecision() = runBlocking {
        val now = Instant.now()
        val evidence = "accepted-wake-user"
        // Reproduces the bug: the planner replied normally, recorded [] as
        // handled, and the older recovery scanner skipped this entire promise.
        CommitmentDecisionRuntime.apply(context, role, "chat:empty:$role",
            listOf(evidence), JSONArray())
        assertTrue(CommitmentDecisionRuntime.handled(context, role, listOf(evidence)))
        assertTrue(CommitmentTaskStore.active(role).isEmpty())

        val task = CommitmentDecisionRuntime.ensureAcceptedWake(
            context, role, "chat:$evidence:$role", listOf(evidence),
            "我好累啊，宝宝，20分钟之后叫我",
            "好，快把手机放下，安心眯一会儿。二十分钟我替你稳稳盯着，到点准时叫你。",
            userAt = now, now = now, zone = zone,
        )
        assertNotNull(task)
        assertEquals(now.plusSeconds(20 * 60), task!!.dueAt)
        assertEquals("send_private_message", task.deliveryAction)
        assertEquals(CommitmentTaskStatus.Scheduled, task.status)
        assertNotNull(task.linkedAlarmId)
        assertEquals(task.id, CommitmentTaskStore.active(role).single().id)
        assertEquals(task.linkedAlarmId, LuluAlarmSystem.list().single {
            it.commitmentTaskId == task.id
        }.id)
        // Re-running the same messages must not generate duplicate alarms.
        val repeated = CommitmentDecisionRuntime.ensureAcceptedWake(
            context, role, "chat:$evidence:$role", listOf(evidence),
            "我好累啊，宝宝，20分钟之后叫我", "好，我来叫你",
            userAt = now, now = now.plusSeconds(10), zone = zone,
        )
        assertEquals(task.id, repeated?.id)
        assertEquals(1, CommitmentTaskStore.active(role).size)
        assertEquals(1, LuluAlarmSystem.list().count { it.commitmentTaskId == task.id })
    }

    @Test fun refusedOrExpiredWakeCannotScheduleAnAlarm() = runBlocking {
        val now = Instant.now()
        assertNull(CommitmentDecisionRuntime.ensureAcceptedWake(
            context, role, "chat:refused:$role", listOf("refused-wake"),
            "20分钟后叫我", "我没办法保证，不能帮你叫醒。", now, now, zone,
        ))
        assertNull(CommitmentDecisionRuntime.ensureAcceptedWake(
            context, role, "chat:expired:$role", listOf("expired-wake"),
            "20分钟后叫我", "好，20分钟后准时叫你。",
            now.minusSeconds(3600), now, zone,
        ))
        assertTrue(CommitmentTaskStore.active(role).isEmpty())
    }

    @Test fun modelChosenOngoingResponsibilityIsVisibleWithoutInventingAnAlarm() = runBlocking {
        // The executor does not parse these words: it receives a typed model decision.
        val changes = JSONArray("""[{"action":"create","goal":"关注备考进展，低落时一起调整","promiseKind":"long_term_supervision","needsClarification":false,"completionCondition":"根据真实进展持续跟进"}]""")
        val receipt = CommitmentDecisionRuntime.apply(context, role, "chat:study:$role", listOf("study-user"), changes)
        val task = CommitmentTaskStore.active(role).single()
        assertEquals(CommitmentTaskStatus.WaitingForFeedback, task.status)
        assertNull(task.dueAt)
        assertNull(task.linkedAlarmId)
        val entry = LuluRepositories.lexicon.snapshot(role).single { it.id == task.lexiconEntryId }
        assertEquals(LexiconSection.Promise, entry.section)
        assertEquals(PromiseKind.LongTermSupervision, entry.promiseKind)
        assertTrue(entry.content.contains("关注备考进展"))
        assertTrue(receipt.contains("持续约定已记入辞海"))
        assertTrue(CommitmentDecisionRuntime.handled(context, role, listOf("study-user")))
        CommitmentDecisionRuntime.apply(context, role, "chat:study:$role", listOf("study-user"), changes)
        assertEquals(1, CommitmentTaskStore.active(role).size)
        val persisted = context.getSharedPreferences("lulu_commitment_tasks", Context.MODE_PRIVATE).getString("tasks_v1", null)
        assertEquals(task.id, decodeCommitmentTasks(persisted).single().id)
    }

    @Test fun modelCorrectionReplacesAlarmAndUpdatesTheSameLexiconEntry() = runBlocking {
        val now = Instant.now()
        val newDue = now.atZone(zone).plusDays(1).withHour(9).withMinute(0).withSecond(0).withNano(0)
        val oldDue = newDue.plusDays(1)
        val format = DateTimeFormatter.ofPattern("M月d日HH:mm")
        val create = JSONArray().put(JSONObject().put("action", "create").put("goal", "${oldDue.format(format)} 叫醒")
            .put("dueAt", oldDue.toInstant().toString()).put("timezone", zone.id).put("deliveryAction", "alarm_only"))
        CommitmentDecisionRuntime.apply(context, role, "chat:old:$role", listOf("old-user"), create)
        val old = CommitmentTaskStore.active(role).single()
        val oldAlarm = old.linkedAlarmId!!
        val change = JSONArray().put(JSONObject().put("action", "reschedule").put("targetTaskId", old.id)
            .put("goal", "${newDue.format(format)} 叫醒").put("dueAt", newDue.toInstant().toString())
            .put("timezone", zone.id).put("deliveryAction", "alarm_only"))
        CommitmentDecisionRuntime.apply(context, role, "chat:correction:$role", listOf("correction-user"), change)
        val updated = CommitmentTaskStore.active(role).single()
        assertEquals(old.id, updated.id)
        assertEquals(old.lexiconEntryId, updated.lexiconEntryId)
        assertEquals("alarm_only", updated.deliveryAction)
        assertEquals(newDue.toInstant(), updated.dueAt)
        assertFalse(LuluAlarmSystem.list().any { it.id == oldAlarm })
        assertEquals(newDue.toInstant(), LuluAlarmSystem.list().single { it.id == updated.linkedAlarmId }.triggerAt)
        val entry = LuluRepositories.lexicon.snapshot(role).single { it.id == updated.lexiconEntryId }
        assertTrue(entry.title.contains(newDue.format(format)))
        assertTrue(entry.content.contains(newDue.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))))
        assertFalse(entry.title.contains(oldDue.format(format)))
    }

    @Test fun anUnacceptedWishDoesNotBecomeATaskAndWrongIdsCannotEditAnotherTask() = runBlocking {
        CommitmentDecisionRuntime.apply(context, role, "chat:wish:$role", listOf("wish-user:group:$role"), JSONArray())
        assertTrue(CommitmentTaskStore.active(role).isEmpty())
        assertTrue(CommitmentDecisionRuntime.handled(context, role, listOf("wish-user")))
        val invalid = JSONArray("""[{"action":"reschedule","targetTaskId":"nonexistent","goal":"改期"}]""")
        val result = runCatching { CommitmentDecisionRuntime.apply(context, role, "chat:bad:$role", listOf("bad-user"), invalid) }
        assertTrue(result.isFailure)
        assertFalse(CommitmentDecisionRuntime.handled(context, role, listOf("bad-user")))
        assertTrue(CommitmentTaskStore.active(role).isEmpty())
    }

    @Test(timeout = 60_000) fun mainChatPlannerSavesASemanticAgreementWithoutAnotherExtractionRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(response("""{"action":"reply","speechIntent":"接受以后一起面对备考低落时刻的约定","reason":"这件事我想认真放在心上","dialogueMove":{"type":"acknowledge","target":"本轮约定","contentIntent":"接受这份托付","maxBubbles":1},"commitmentChanges":[{"action":"create","goal":"备考低落时一起调整，持续关注进展","promiseKind":"long_term_supervision","needsClarification":false,"completionCondition":"在后续真实互动里持续跟进"}]}"""))
        server.enqueue(response("""{"action":"reply","bubbles":[{"text":"好，这件事我记着了。"}]}"""))
        server.start()
        try {
            configure(server)
            com.jiacimu.lulu.system.LuluDeviceToolBridge.initialize(context)
            com.jiacimu.lulu.study.PostgraduateExamStores.initialize(context)
            com.jiacimu.lulu.study.StarWishStores.initialize(context)
            val text = "以后低落的时候拉我一把，别让我掉队。"
            SharedExperienceTimeline.record("semantic-user", role, "私聊", "用户", text,
                triggerExtraction = false, sessionId = "semantic-chat", source = "message",
                evidenceKind = EventEvidenceKind.UserStatement)
            val result = com.jiacimu.lulu.system.LuluDeviceToolBridge.respond(role, "", text, "test",
                userEvidenceIds = listOf("semantic-user")).getOrThrow()
            assertTrue(result.text.contains("记着了"))
            assertEquals("备考低落时一起调整，持续关注进展", CommitmentTaskStore.active(role).single().goal)
            assertEquals(2, server.requestCount)
            val plannerRequest = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            val expressionRequest = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(plannerRequest.contains("commitmentChanges"))
            assertTrue(expressionRequest.contains("本轮约定真实回执"))
            assertTrue(CommitmentDecisionRuntime.handled(context, role, listOf("semantic-user")))
        } finally { server.shutdown() }
    }

    private fun response(content: String) = MockResponse().setHeader("Content-Type", "application/json")
        .setBody(JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", content)).put("finish_reason", "stop"))).toString())

    private fun configure(server: MockWebServer) {
        val config = LuluAiServices.connectionStore.saveConfiguration(null, "test", server.url("/").toString(), "test-only")
        LuluAiServices.connectionStore.addArchive(config.id, "test")
    }

    private fun resetTaskPreferences() = CommitmentTaskStore::class.java.getDeclaredField("prefs")
        .apply { isAccessible = true }.set(CommitmentTaskStore, null)
}
