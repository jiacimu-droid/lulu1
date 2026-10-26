package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelUsage
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

    @Test fun commitmentExtractionUsesBackgroundModelAndNeverPaysForChatArchive() = runBlocking {
        val chat = MockWebServer()
        val globalDefault = MockWebServer()
        val memoryExtraction = MockWebServer()
        globalDefault.enqueue(response("[]"))
        memoryExtraction.enqueue(response("[]"))
        chat.start(); globalDefault.start(); memoryExtraction.start()
        val advanced = context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE)
        try {
            val library = LuluAiServices.connectionStore
            val defaultConfig = library.saveConfiguration(null, "cheap-global-default",
                globalDefault.url("/").toString(), "default-test-key")
            val defaultArchive = library.addArchive(defaultConfig.id, "default-cheap-model")
            val chatConfig = library.saveConfiguration(null, "expensive-chat",
                chat.url("/").toString(), "chat-test-key")
            val chatArchive = library.addArchive(chatConfig.id, "expensive-chat-model")
            library.selectArchive(chatArchive.id, ModelUsage.Chat)
            library.selectArchive(defaultArchive.id)

            advanced.edit()
                .putString("memory_extract_url", memoryExtraction.url("/").toString())
                .putString("memory_extract_key", "memory-test-key")
                .putString("memory_extract_model", "memory-cheap-model")
                .commit()
            MemoryModelRuntime.initialize(context)
            assertTrue(extractCommitmentTaskDrafts(role, "你会记住这件事吗？",
                "会，我会记着。", emptyList()).isEmpty())
            val cheapRequest = memoryExtraction.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(cheapRequest.contains("用户本轮"))
            assertTrue(cheapRequest.contains("角色本轮完整回复"))
            assertTrue(cheapRequest.contains("memory-cheap-model"))
            assertEquals(0, chat.requestCount)
            assertEquals(0, globalDefault.requestCount)

            // No separate memory model configured: use the global active archive,
            // never the expensive per-chat selection.
            advanced.edit().remove("memory_extract_url").remove("memory_extract_key")
                .remove("memory_extract_model").commit()
            assertTrue(extractCommitmentTaskDrafts(role, "今天有新约定吗？",
                "没有。", emptyList()).isEmpty())
            val defaultRequest = globalDefault.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(defaultRequest.contains("default-cheap-model"))
            assertEquals(0, chat.requestCount)
            assertEquals(1, memoryExtraction.requestCount)
            assertEquals(1, globalDefault.requestCount)
        } finally {
            advanced.edit().remove("memory_extract_url").remove("memory_extract_key")
                .remove("memory_extract_model").commit()
            chat.shutdown(); globalDefault.shutdown(); memoryExtraction.shutdown()
        }
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

    @Test fun acceptedTwentyMinuteWakeIsScheduledFromTheModelDraftAfterMainMissedIt() = runBlocking {
        val due = Instant.now().plusSeconds(20 * 60)
        val evidence = "accepted-wake-user"
        // The full role decision produced a spoken promise but no typed change.
        CommitmentDecisionRuntime.apply(context, role, "chat:empty:$role",
            listOf(evidence), JSONArray())
        assertTrue(CommitmentTaskStore.active(role).isEmpty())

        val model = CommitmentTaskDraft(
            action = "create", goal = "20分钟后叫醒用户",
            dueAt = due, timezone = zone.id,
            deliveryAction = "alarm_only",
        )
        val first = CommitmentDecisionRuntime.applyModelRecovery(
            context, role, "chat:$evidence:$role", listOf(evidence), listOf(model))
        assertEquals(1, first.size)
        val task = CommitmentTaskStore.active(role).single()
        assertEquals(due, task.dueAt)
        assertEquals("alarm_only", task.deliveryAction)
        assertEquals(CommitmentTaskStatus.Scheduled, task.status)
        assertEquals(task.id, LuluAlarmSystem.list().single {
            it.commitmentTaskId == task.id
        }.commitmentTaskId)
        // The same model output must not register another real alarm.
        val repeated = CommitmentDecisionRuntime.applyModelRecovery(
            context, role, "chat:$evidence:$role", listOf(evidence), listOf(model))
        assertTrue(repeated.isEmpty())
        assertEquals(1, LuluAlarmSystem.list().count { it.commitmentTaskId == task.id })
    }

    @Test fun modelDecidesDeliveryRouteAndTheExecutorRejectsQuietWake() = runBlocking {
        val due = Instant.now().plusSeconds(20 * 60)
        val quiet = parseCommitmentTaskDrafts(
            """[{"action":"create","goal":"叫醒用户","dueAt":"${due}","deliveryAction":"send_private_message"}]"""
        ).single()
        assertTrue(quiet.needsClarification)
        CommitmentDecisionRuntime.applyModelRecovery(
            context, role, "chat:quiet:$role", listOf("quiet"), listOf(quiet))
        val pending = CommitmentTaskStore.active(role).single()
        assertEquals(CommitmentTaskStatus.NeedsClarification, pending.status)
        assertNull(pending.linkedAlarmId)
        assertTrue(CommitmentDecisionProtocol.instruction.contains("自主决定"))
        assertTrue(CommitmentDecisionProtocol.instruction.contains("alarm_only"))
    }

    @Test fun modelRecoveryDoesNotInventCommitmentsOrRescheduleUnknownTargets() = runBlocking {
        val empty = CommitmentDecisionRuntime.applyModelRecovery(
            context, role, "chat:no-promise:$role", listOf("no-promise"), emptyList())
        assertTrue(empty.isEmpty())
        assertTrue(CommitmentTaskStore.snapshot(role).isEmpty())
        val wrong = CommitmentTaskDraft(action = "reschedule",
            targetTaskId = "not-a-real-task", goal = "改为今晚叫醒",
            dueAt = Instant.now().plusSeconds(8000), deliveryAction = "alarm_only")
        assertTrue(runCatching {
            CommitmentDecisionRuntime.applyModelRecovery(context, role,
                "chat:bad:$role", listOf("bad"), listOf(wrong))
        }.isFailure)
        assertTrue(CommitmentTaskStore.snapshot(role).isEmpty())
    }

    @Test fun modelRecoveryUpdatesItsExactTargetAndCancelsTheWrongAlarm() = runBlocking {
        val later = Instant.now().plusSeconds(27 * 3600L)
        val agreed = Instant.now().plusSeconds(3 * 3600L)
        val original = CommitmentTaskStore.save(CommitmentTask(
            characterId = role, goal = "明天8点叫醒", dueAt = later,
            status = CommitmentTaskStatus.Scheduled,
            timezone = zone.id, deliveryAction = "alarm_only",
            sourceEventIds = listOf("older-user"),
        ))
        val old = LuluAlarmSystem.create(role, "测试角色", later, original.goal,
            commitmentTaskId = original.id).getOrThrow()
        CommitmentTaskStore.update(original.id) { it.copy(linkedAlarmId = old.id) }
        val newDraft = CommitmentTaskDraft(action = "reschedule",
            targetTaskId = original.id, dueAt = agreed,
            goal = "今天8点叫醒", timezone = zone.id,
            deliveryAction = "alarm_only")
        // No Kotlin code reads or interprets these Chinese time words.
        val changed = CommitmentDecisionRuntime.applyModelRecovery(context, role,
            "chat:model-correction:$role", listOf("new-user"), listOf(newDraft))
        assertEquals(1, changed.size)
        assertEquals(original.id, changed.single().id)
        assertEquals(agreed, changed.single().dueAt)
        assertFalse(LuluAlarmSystem.list().any { it.id == old.id })
        assertEquals(1, LuluAlarmSystem.list().count { it.commitmentTaskId == original.id })
        assertEquals(1, CommitmentTaskStore.snapshot(role).count { it.id == original.id })
    }

    @Test fun absentWakeChannelFromMainModelStaysPendingUntilRoleChooses() = runBlocking {
        val due = Instant.now().plusSeconds(20 * 60)
        val json = JSONArray().put(JSONObject()
            .put("action", "create").put("goal", "20分钟后叫醒用户")
            .put("dueAt", due.toString()).put("timezone", zone.id))
        val receipt = CommitmentDecisionRuntime.apply(context, role,
            "chat:pending:$role", listOf("pending-wake"), json)
        val pending = CommitmentTaskStore.snapshot(role).single()
        assertEquals(CommitmentTaskStatus.NeedsClarification, pending.status)
        assertNull(pending.linkedAlarmId)
        assertFalse(LuluAlarmSystem.list().any { it.commitmentTaskId == pending.id })
        assertTrue(receipt.contains("NeedsClarification"))
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
        assertTrue(receipt.contains("持续约定已保存"))
        assertTrue(receipt.contains("日程的未定时约定"))
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
