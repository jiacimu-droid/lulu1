package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ScopedModelSelections
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ResidentDailyContinuityTest {
    // The production world has a deliberate twelve-resident limit. These
    // Robolectric cases must not consume one another's persistent residents:
    // isolate ONLY the test world's expansion fixture and restore it afterward.
    private var savedResidents: List<DigitalWorldResident> = emptyList()
    private var savedResidentJson: String? = null
    private lateinit var residentPrefs: android.content.SharedPreferences

    private fun residentField() = DigitalWorldExpansionStore::class.java
        .getDeclaredField("residents").apply { isAccessible = true }

    @org.junit.Before fun initializeWorkerHost() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        if (runCatching { androidx.work.WorkManager.getInstance(app) }.isFailure)
            androidx.work.WorkManager.initialize(app, androidx.work.Configuration.Builder().build())
        DigitalWorldExpansionStore.initialize(app)
        val prefsField = DigitalWorldExpansionStore::class.java
            .getDeclaredField("prefs").apply { isAccessible = true }
        residentPrefs = prefsField.get(DigitalWorldExpansionStore) as android.content.SharedPreferences
        @Suppress("UNCHECKED_CAST")
        savedResidents = residentField().get(DigitalWorldExpansionStore) as List<DigitalWorldResident>
        savedResidentJson = residentPrefs.getString("residents", null)
        residentField().set(DigitalWorldExpansionStore, emptyList<DigitalWorldResident>())
        assertTrue(residentPrefs.edit().putString("residents", "[]").commit())
    }

    @org.junit.After fun restoreWorldFixture() {
        residentField().set(DigitalWorldExpansionStore, savedResidents)
        val edit = residentPrefs.edit()
        if (savedResidentJson == null) edit.remove("residents")
        else edit.putString("residents", savedResidentJson)
        assertTrue(edit.commit())
    }


    @Test fun waitingReviewTimeUsesResidentsClockAndRejectsInvalidExplicitTimes() {
        val now = Instant.parse("2026-10-28T07:59:45Z")
        val zone = java.time.ZoneId.of("UTC")
        assertEquals(Instant.parse("2026-10-29T08:00:00Z"),
            ResidentDailyDecision.waitReviewAt(JSONObject().put("choice", "wait"), now, zone))
        assertEquals(Instant.parse("2026-10-28T09:00:00Z"),
            ResidentDailyDecision.waitReviewAt(JSONObject().put("reviewAt",
                "2026-10-28T17:00:00+08:00"), now, zone))
        assertNull(ResidentDailyDecision.waitReviewAt(JSONObject().put("reviewAt",
            "not-a-time"), now, zone))
        assertNull(ResidentDailyDecision.waitReviewAt(JSONObject().put("reviewAt",
            "2026-10-28T07:30:00Z"), now, zone))
    }


    @Test fun delayedResidentReviewCannotOverwriteNewerUserReschedule() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val role = "resident:stale-review-${UUID.randomUUID()}"
        // This guard is the same persistence authority used by residents.
        val original = RoleDayScheduleStore.arrange(app, role, "整理庭院里的花草",
            "想记录今天的变化", now.plusSeconds(600), now,
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "花草记录"))
        val newer = RoleDayScheduleStore.reschedule(app, role, original.id,
            now.plusSeconds(900), "后来决定晚一点", now)
        try {
            runCatching {
                RoleDayScheduleStore.reschedule(app, role, original.id,
                    now.plusSeconds(1200), "迟到的旧等待决定", now,
                    expectedDueAt = original.dueAt)
            }.onSuccess { fail("旧等待结果不应覆盖新日程") }
            val stillNew = RoleDayScheduleStore.plans(app, role).single { it.id == original.id }
            assertEquals(newer.dueAt, stillNew.dueAt)
            assertEquals(newer.rescheduleCount, stillNew.rescheduleCount)
        } finally {
            RoleDayScheduleStore.remove(app, role, original.id)
        }
    }

    @Test(timeout = 60_000) fun waitReplansOneUnfinishedAppointmentAndNextTurnMakesNewDecision() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "等待复议居民${UUID.randomUUID().toString().take(6)}",
            identity = "庭院访客", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val text = "等待时自己想发的消息-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "等朋友到来后分享",
            "先看朋友是否有空", now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "原计划不应自动发"))
        val review = now.plusSeconds(240)
        val first = JSONObject().put("choice", "wait")
            .put("reason", "想等朋友回复").put("waitCondition", "对方确认可见面")
            .put("reviewAt", review.toString()).put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment")
                    .put("args", JSONObject().put("text", text))))
        val second = JSONObject().put("choice", "cancel")
            .put("reason", "今天不打扰了").put("steps", JSONArray())
        fun modelAnswer(json: JSONObject) = MockResponse().setBody(
            JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", json.toString())))).toString())
        val server = MockWebServer()
        server.enqueue(modelAnswer(first))
        server.enqueue(modelAnswer(second))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "wait-recheck-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            val awaiting = RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }
            assertEquals("planned", awaiting.status)
            assertEquals(review, awaiting.dueAt)
            assertEquals(1, awaiting.rescheduleCount)
            assertTrue(awaiting.resultSummary.contains("对方确认可见面"))
            assertFalse(awaiting.status == "completed")
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == text })
            assertEquals(1, server.requestCount)
            // A new due-time version is a NEW model choice, not a replay of
            // the old 'wait' response or its already performed side action.
            assertTrue(ResidentDailyDecision.runDuePlan(app, awaiting, review.plusSeconds(1)))
            assertEquals("cancelled",
                RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
            assertEquals(2, server.requestCount)
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == text })
        } finally { server.shutdown() }
    }


    @Test(timeout = 60_000) fun nativeRescheduleKeepsItsIndependentStepsAndDoesNotRepeatModelCall() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "自主改期居民${UUID.randomUUID().toString().take(6)}",
            identity = "散步者", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val originalText = "准备以后分享-${UUID.randomUUID()}"
        val independent = "改期时突然想分享-${UUID.randomUUID()}"
        val dependent = "必须依赖原活动完成-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "分享今天的见闻",
            "想把看到的事情记下来", now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", originalText))
        val next = now.plusSeconds(600)
        val choice = JSONObject().put("choice", "reschedule")
            .put("dueAt", next.toString()).put("reason", "想晚一点再做")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", true)
                    .put("args", JSONObject().put("text", dependent)))
                .put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", false)
                    .put("args", JSONObject().put("text", independent))))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", choice.toString())))).toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "resident-reschedule-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            val changed = RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }
            assertEquals("planned", changed.status)
            assertEquals(next, changed.dueAt)
            assertEquals(1, changed.rescheduleCount)
            assertEquals(1, server.requestCount)
            val all = MomentsStore.posts.value.filter { it.authorCharacterId == actor }
                .map { it.content }
            assertTrue(all.contains(independent))
            assertFalse(all.contains(dependent))
            assertFalse(all.contains(originalText))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == independent
            })
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun nativeCancelKeepsIndependentReceiptWithoutCancellingDecision() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "自主撤销居民${UUID.randomUUID().toString().take(6)}",
            identity = "花店学徒", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val independent = "取消旧计划但顺手分享-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "原定整理花园",
            "今天想打理院子", now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "不应自动执行的原计划"))
        val choice = JSONObject().put("choice", "cancel")
            .put("reason", "今天更想独处")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", false)
                    .put("args", JSONObject().put("text", independent))))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(JSONObject()
            .put("choices", JSONArray().put(JSONObject().put("message",
                JSONObject().put("content", choice.toString())))).toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "resident-cancel-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals("cancelled",
                RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
            assertEquals(1, server.requestCount)
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == independent
            })
            assertFalse(ResidentDailyDecision.hasSavedDueToResume(app, plan))
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun cancelledDuringDecisionKeepsIndependentStepsAndRejectsDependentOnes() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "多步居民${UUID.randomUUID().toString().take(6)}", identity = "院子观察者",
            homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD, createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val plannedText = "旧日程草稿-${UUID.randomUUID()}"
        val independentText = "独立的新想法-${UUID.randomUUID()}"
        val dependentText = "依赖原计划成功，不应发布-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "分享刚才的所见", "自己想分享",
            now.minusSeconds(120), now.minusSeconds(600), activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", plannedText))
        val output = JSONObject().put("choice", "execute").put("reason", "原来想分享")
            .put("args", JSONObject().put("text", plannedText))
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment").put("requiresPreviousSuccess", true)
                    .put("args", JSONObject().put("text", dependentText)))
                .put(JSONObject().put("tool", "publish_moment").put("requiresPreviousSuccess", false)
                    .put("args", JSONObject().put("text", independentText))))
        val server = MockWebServer()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                RoleDayScheduleStore.cancel(app, actor, plan.id, "思考期间撤销原计划")
                return MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
                    JSONObject().put("message", JSONObject().put("content", output.toString())))).toString())
            }
        }
        server.start()
        try {
            MigratedDomainStores.characters.settings.value.keys.filterNot { it.startsWith("resident:") }
                .forEach { CompanionOnlineStore.resetCharacter(it) }
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "wake-test",
                server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals("cancelled", RoleDayScheduleStore.plans(app, actor).first { it.id == plan.id }.status)
            val published = MomentsStore.posts.value.filter { it.authorCharacterId == actor }.map { it.content }
            assertTrue(published.contains(independentText))
            assertFalse(published.contains(plannedText))
            assertFalse(published.contains(dependentText))
            assertTrue(SharedExperienceTimeline.eventsByIds(actor, listOf(
                "tool-rejected-$actor-resident-plan-${plan.id}-${plan.dueAt.epochSecond}")).isNotEmpty())
        } finally { server.shutdown() }
    }


    @Test fun oldResidentWakeVersionCannotCancelOrClaimNewAppointment() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val role = "resident:version-${UUID.randomUUID()}"
        val old = RoleDayScheduleStore.arrange(app, role, "分享庭院观察",
            "自己想留下今天的见闻", now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "原计划"))
        try {
            val args = JSONObject().put("planId", old.id).put("text", "原计划")
            assertTrue(RoleDayScheduleStore.canBegin(app, role, old.id,
                "publish_moment", args, now, expectedDueAt = old.dueAt))
            assertFalse(RoleDayScheduleStore.canBegin(app, role, old.id,
                "publish_moment", args, now, expectedDueAt = old.dueAt.plusSeconds(1)))
            val newer = RoleDayScheduleStore.reschedule(app, role, old.id,
                now.plusSeconds(900), "后来改期", now)
            assertFalse(RoleDayScheduleStore.cancel(app, role, old.id,
                "旧决定迟到", now, expectedDueAt = old.dueAt))
            assertNull(RoleDayScheduleStore.markDue(app, role, old.id,
                expectedDueAt = old.dueAt))
            val current = RoleDayScheduleStore.plans(app, role).single { it.id == old.id }
            assertEquals("planned", current.status)
            assertEquals(newer.dueAt, current.dueAt)
        } finally { RoleDayScheduleStore.remove(app, role, old.id) }
    }

    @Test(timeout = 60_000) fun lateResidentCancelAndExecuteCannotOverwriteRescheduledPlan() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        for (choice in listOf("cancel", "execute")) {
            val now = Instant.now()
            val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
                name = "迟到决策居民${UUID.randomUUID().toString().take(5)}",
                identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
                createdAt = now.minusSeconds(3600)))
            ResidentCharacterBridge.sync(app, now)
            val actor = ResidentCharacterBridge.characterId(native.id)
            val primary = "旧主动作禁止发布-${UUID.randomUUID()}"
            val independent = "独立分享仅一次-${UUID.randomUUID()}"
            val plan = RoleDayScheduleStore.arrange(app, actor, "分享庭院见闻",
                "自己想分享观察", now.minusSeconds(120), now.minusSeconds(600),
                activityAction = "publish_moment",
                activityArgs = JSONObject().put("text", primary))
            val next = now.plusSeconds(900)
            val decision = JSONObject().put("choice", choice).put("reason", "旧决定")
                .put("args", JSONObject().put("text", primary))
                .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", false)
                    .put("args", JSONObject().put("text", independent))))
            val server = MockWebServer()
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                    RoleDayScheduleStore.reschedule(app, actor, plan.id, next,
                        "模型思考期间主人已改期", now)
                    return answer(decision.toString())
                }
            }
            server.start()
            try {
                val config = LuluAiServices.connectionStore.saveConfiguration(null,
                    "late-${choice}-${UUID.randomUUID()}", server.url("/").toString(), "test")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
                assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
                val current = RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }
                assertEquals("planned", current.status)
                assertEquals(next, current.dueAt)
                assertFalse(MomentsStore.posts.value.any {
                    it.authorCharacterId == actor && it.content == primary })
                assertEquals(1, MomentsStore.posts.value.count {
                    it.authorCharacterId == actor && it.content == independent })
                val key = "execute:${plan.id}:${plan.dueAt}"
                val prefs = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                    android.content.Context.MODE_PRIVATE)
                assertEquals("superseded", prefs.getString("due-phase:$key", ""))
                assertFalse(ResidentDailyDecision.hasSavedDueToResume(app, plan))
                if (choice == "execute") assertTrue(
                    SharedExperienceTimeline.eventsByIds(actor, listOf(
                        "tool-rejected-$actor-resident-plan-${plan.id}-${plan.dueAt.epochSecond}"))
                        .isNotEmpty())
                assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
                assertEquals(1, server.requestCount)
                assertEquals(1, MomentsStore.posts.value.count {
                    it.authorCharacterId == actor && it.content == independent })
            } finally { server.shutdown() }
        }
    }

    @Test(timeout = 60_000) fun savedDueDecisionReplaysReceiptWithoutAnotherModelCall() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "续行居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val post = "中断后才发布的真实想法-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "分享庭院见闻", "我自己想分享",
            now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment", activityArgs = JSONObject().put("text", post))
        val key = "execute:${plan.id}:${plan.dueAt}"
        val decision = JSONObject().put("choice", "execute")
            .put("reason", "我想兑现原本的分享")
            .put("args", JSONObject().put("text", post))
            .put("steps", JSONArray())
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1", android.content.Context.MODE_PRIVATE)
        p.edit().putBoolean(key, true).putString("due-decision:$key", decision.toString())
            .putString("due-phase:$key", "decided").commit()
        assertTrue(ResidentDailyDecision.hasSavedDueToResume(app, plan))
        val server = MockWebServer()
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "due-resume-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(0, server.requestCount)
            assertEquals("completed", RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
            assertEquals(1, MomentsStore.posts.value.count { it.authorCharacterId == actor && it.content == post })
            assertFalse(ResidentDailyDecision.hasSavedDueToResume(app, plan))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(1, MomentsStore.posts.value.count { it.authorCharacterId == actor && it.content == post })
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 60_000) fun completedResidentPrimaryResumesDependentSideStepWithoutReposting() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "已执行续接居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val primary = "已经发布的主活动-${UUID.randomUUID()}"
        val dependent = "主活动成功后的独立分享-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "分享庭院见闻", "我想分享这件事",
            now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment", activityArgs = JSONObject().put("text", primary))
        val key = "execute:${plan.id}:${plan.dueAt}"
        val choice = JSONObject().put("choice", "execute")
            .put("reason", "先分享见闻，再告诉朋友后续")
            .put("args", JSONObject().put("text", primary))
            .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                .put("requiresPreviousSuccess", true)
                .put("args", JSONObject().put("text", dependent))))
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
            android.content.Context.MODE_PRIVATE)
        assertTrue(p.edit().putBoolean(key, true)
            .putString("due-decision:$key", choice.toString())
            .putString("due-phase:$key", "decided").commit())
        // Simulate process death after the primary ToolRouter receipt has
        // settled the calendar, but before OrderedCharacterActions runs.
        val primaryReceipt = JSONObject(ToolRouter.execute(app, actor, "publish_moment",
            JSONObject().put("text", primary).put("planId", plan.id),
            requestId = "resident-plan-${plan.id}-${plan.dueAt.epochSecond}",
            autonomousLifeTurn = true))
        assertTrue(primaryReceipt.toString(), primaryReceipt.optBoolean("success"))
        val completed = RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }
        assertEquals("completed", completed.status)
        assertTrue(completed.resultEvidenceId.isNotBlank())
        assertTrue(ResidentDailyDecision.hasSavedDueToResume(app, completed))
        // Restoration must see the finished primary as a pending continuation.
        RoleDayScheduleStore.restorePending(app, now)
        ScopedModelSelections.initialize(app)
        ScopedModelSelections.clear(ResidentDailyDecision.SCOPE)
        assertTrue(ResidentDailyDecision.runDuePlan(app, completed, now))
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == primary
        })
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == dependent
        })
        assertFalse(ResidentDailyDecision.hasSavedDueToResume(app, completed))
        assertFalse(ResidentDailyDecision.runDuePlan(app, completed, now))
        assertEquals(1, MomentsStore.posts.value.count {
            it.authorCharacterId == actor && it.content == dependent
        })
    }

    @Test(timeout = 60_000) fun invalidRescheduleStillRunsIndependentStepsAndReplaysIdempotently() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "改期恢复居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val original = "原定分享-${UUID.randomUUID()}"
        val independent = "不依赖改期的分享-${UUID.randomUUID()}"
        val dependent = "依赖改期成功的分享-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "分享今天的观察",
            "自己想分享", now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment", activityArgs = JSONObject().put("text", original))
        val decision = JSONObject().put("choice", "reschedule")
            .put("dueAt", "invalid-date").put("reason", "想改天再发")
            .put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", true)
                    .put("args", JSONObject().put("text", dependent)))
                .put(JSONObject().put("tool", "publish_moment")
                    .put("requiresPreviousSuccess", false)
                    .put("args", JSONObject().put("text", independent))))
        val key = "execute:${plan.id}:${plan.dueAt}"
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
            android.content.Context.MODE_PRIVATE)
        assertTrue(p.edit().putBoolean(key, true)
            .putString("due-decision:$key", decision.toString())
            .putString("due-phase:$key", "decided").commit())
        val server = MockWebServer()
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "invalid-reschedule-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            val posts = MomentsStore.posts.value.filter { it.authorCharacterId == actor }
                .map { it.content }
            assertTrue(posts.contains(independent))
            assertFalse(posts.contains(dependent))
            assertFalse(posts.contains(original))
            assertEquals("needs_recovery", p.getString("due-phase:$key", ""))
            assertTrue(ResidentDailyDecision.hasSavedDueToResume(app, plan))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == independent
            })
            assertFalse(ResidentDailyDecision.hasSavedDueToResume(app, plan))
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }

    @Test fun savedDecisionRecoveryIsLimitedAndNeverReissuesAnUncertainModelRequest() {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        val nativeId = "resume-budget-" + UUID.randomUUID()
        val now = Instant.now()
        val day = ResidentDailyDecision.date(now)
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1", android.content.Context.MODE_PRIVATE)
        assertFalse(ResidentDailyDecision.hasSavedDayToResume(app, nativeId, now))
        p.edit().putString("attempt:$nativeId", day)
            .putString("phase:$nativeId:$day", "uncertain").commit()
        assertFalse(ResidentDailyDecision.hasSavedDayToResume(app, nativeId, now))
        p.edit().putString("decision:$nativeId:$day", JSONObject().put("agenda", JSONArray()).toString())
            .putString("phase:$nativeId:$day", "decided").commit()
        assertTrue(ResidentDailyDecision.hasSavedDayToResume(app, nativeId, now))
        p.edit().putInt("recoveries:$nativeId:$day", 2).commit()
        assertFalse(ResidentDailyDecision.hasSavedDayToResume(app, nativeId, now))
        p.edit().putInt("recoveries:$nativeId:$day", 0)
            .putString("phase:$nativeId:$day", "finished").commit()
        assertFalse(ResidentDailyDecision.hasSavedDayToResume(app, nativeId, now))
    }

    @Test(timeout = 60_000) fun cachedDailyDecisionResumesWithoutAnotherModelRequest() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "恢复日程居民${UUID.randomUUID().toString().take(5)}",
            identity = "庭院居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val day = ResidentDailyDecision.date(now)
        val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1", android.content.Context.MODE_PRIVATE)
        val decision = JSONObject().put("agenda", JSONArray()).put("lunch", "home")
            .put("evening", "courtyard").put("thought", "想在庭院看看天空")
        p.edit().putString("attempt:${native.id}", day)
            .putString("decision:${native.id}:$day", decision.toString())
            .putString("phase:${native.id}:$day", "decided").commit()
        val server = MockWebServer()
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "resume-only-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            // A persisted decision must not depend on the original archive
            // still being selected after an app restart or settings change.
            ScopedModelSelections.clear(ResidentDailyDecision.SCOPE)
            assertTrue(ResidentDailyDecision.hasSavedDayToResume(app, native.id, now))
            assertTrue(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(0, server.requestCount)
            assertEquals("home", ResidentDailyDecision.planFor(app, native.id, now)?.optString("lunch"))
            assertFalse(ResidentDailyDecision.hasSavedDayToResume(app, native.id, now))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
        } finally {
            server.shutdown()
        }
    }

    @Test(timeout = 60_000) fun dailyPlanPreservesChosenFriendshipAndFurnitureWithoutExecutingThem() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDate.now(zone).atTime(8, 0).atZone(zone).toInstant()
        val a = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "布置家园居民${UUID.randomUUID().toString().take(5)}",
            identity = "花艺爱好者", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        val b = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "庭院熟人${UUID.randomUUID().toString().take(5)}",
            identity = "庭院访客", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        CharacterFriendRequests.initialize(app)
        val role = ResidentCharacterBridge.characterId(a.id)
        val peer = ResidentCharacterBridge.characterId(b.id)
        // Newly registered citizens start in their own homes even if the
        // caller describes a courtyard as their intended neighborhood.
        // Persist an actual trip for each before asserting acquaintance.
        assertNotNull(DigitalWorldExpansionStore.moveResident(
            a.id, DigitalWorldPublicPlaces.COURTYARD, now))
        assertNotNull(DigitalWorldExpansionStore.moveResident(
            b.id, DigitalWorldPublicPlaces.COURTYARD, now))
        assertTrue(ResidentSocialMailbox.canContact(role, peer))
        val agenda = JSONArray().put(JSONObject().put("at", "11:00")
            .put("title", "申请和新认识的人做好友").put("intent", "想慢慢继续交流")
            .put("activityAction", "send_friend_request")
            .put("targetCharacterId", peer)
            .put("activityArgs", JSONObject().put("greeting", "很高兴在庭院认识你")))
            .put(JSONObject().put("at", "12:00")
                .put("title", "回家布置一盏灯").put("intent", "想让房间温馨一点")
                .put("activityAction", "digital_world_action")
                .put("activityArgs", JSONObject().put("worldAction", "build_home_item")
                    .put("name", "窗边小灯").put("itemType", "lamp")
                    .put("appearance", "蓝灰色小台灯").put("position", "窗边桌上")))
        val saved = ResidentDailyDecision.saveAgenda(app, a, JSONObject().put("agenda", agenda), now)
        assertEquals(2, saved.size)
        assertEquals("send_friend_request", saved[0].activityAction)
        assertEquals(peer, JSONObject(saved[0].activityArgs).optString("targetCharacterId"))
        assertEquals("build_home_item", JSONObject(saved[1].activityArgs).optString("worldAction"))
        assertTrue(CharacterFriendRequests.historyFor(role).isEmpty())
        assertFalse(DigitalWorldStore.itemsAtHome(role).any { it.name == "窗边小灯" })
        assertTrue(RoleDayScheduleStore.plans(app, role).any { it.id == saved[1].id })
        // A request already waiting for an independent response cannot be
        // silently queued a second time in tomorrow's plan.
        CharacterFriendRequests.send(role, peer, "希望以后聊聊", now)
        val repeated = ResidentDailyDecision.saveAgenda(app, a,
            JSONObject().put("agenda", JSONArray().put(agenda.getJSONObject(0))), now)
        assertTrue(repeated.isEmpty())
    }

    @Test(timeout = 60_000) fun invalidFurnitureProposalNeverBecomesAnExecutableAppointment() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val zone = java.time.ZoneId.systemDefault()
        val now = java.time.LocalDate.now(zone).atTime(8, 0).atZone(zone).toInstant()
        val person = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "谨慎布置居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            currentLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val bad = JSONArray().put(JSONObject().put("at", "13:30")
            .put("title", "买没有看到的家具").put("intent", "想换换心情")
            .put("activityAction", "digital_world_action")
            .put("activityArgs", JSONObject().put("worldAction", "build_home_item")
                .put("name", "没有外观的家具")))
            .put(JSONObject().put("at", "15:00")
                .put("title", "挪动不存在的家具").put("intent", "觉得它太占地方")
                .put("activityAction", "digital_world_action")
                .put("activityArgs", JSONObject().put("worldAction", "move_home_item")
                    .put("itemId", "not-owned").put("position", "靠墙")))
        assertTrue(ResidentDailyDecision.saveAgenda(app, person,
            JSONObject().put("agenda", bad), now).isEmpty())
    }

    @Test fun fixedMorningSlotAndAgendaTimesAreCalendarBased() {
        val zone = java.time.ZoneId.of("Asia/Shanghai")
        val day = java.time.LocalDate.of(2026, 10, 24)
        val seven = day.atTime(7, 30).atZone(zone).toInstant()
        val eight = day.atTime(8, 0).atZone(zone).toInstant()
        assertEquals(eight, ResidentDailyDecision.nextPlanningTime(seven, zone))
        assertEquals(day.plusDays(1).atTime(8, 0).atZone(zone).toInstant(),
            ResidentDailyDecision.nextPlanningTime(eight, zone))
        assertEquals(day.atTime(18, 20).atZone(zone).toInstant(),
            ResidentDailyDecision.agendaTime(seven, "18:20", zone))
        assertNull(ResidentDailyDecision.agendaTime(seven, "07:25", zone))
        assertNull(ResidentDailyDecision.agendaTime(seven, "36:91", zone))
    }

    @Test fun residentsShareTheRealCalendarAndCannotPlanMessagesToInventedFriends() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        DigitalWorldExpansionStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        val localDay = java.time.LocalDate.now().plusDays(1)
        val today = localDay.atTime(8, 5).atZone(java.time.ZoneId.systemDefault()).toInstant()
        val person = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "排程居民${UUID.randomUUID().toString().take(5)}",
            identity = "世界书籍整理员", homeLocationCode = DigitalWorldPublicPlaces.CAFE,
            currentLocationCode = DigitalWorldPublicPlaces.CAFE, createdAt = today))
        val peer = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "同场朋友${UUID.randomUUID().toString().take(5)}",
            identity = "手工艺工作者", homeLocationCode = DigitalWorldPublicPlaces.CAFE,
            currentLocationCode = DigitalWorldPublicPlaces.CAFE, createdAt = today))
        ResidentCharacterBridge.sync(app, today)
        val actor = ResidentCharacterBridge.characterId(person.id)
        val friend = ResidentCharacterBridge.characterId(peer.id)
        // Registration creates separate private homes, even if the input
        // proposed a public currentLocationCode. Only confirmed world movement
        // can establish co-presence and permission to initiate a message.
        assertFalse(ResidentSocialMailbox.canContact(actor, friend))
        assertNotNull(DigitalWorldExpansionStore.moveResident(person.id,
            DigitalWorldPublicPlaces.CAFE, today))
        assertNotNull(DigitalWorldExpansionStore.moveResident(peer.id,
            DigitalWorldPublicPlaces.CAFE, today))
        assertEquals(DigitalWorldPublicPlaces.CAFE, DigitalWorldStore.locationOf(actor))
        assertEquals(DigitalWorldPublicPlaces.CAFE, DigitalWorldStore.locationOf(friend))
        assertTrue(ResidentSocialMailbox.visibleContacts(actor).contains(friend))
        fun item(clock: String, title: String, action: String, args: JSONObject,
                 recipient: String = "", place: String = "") = JSONObject()
            .put("at", clock).put("title", title).put("intent", "我自己想认真做这件事")
            .put("activityAction", action).put("activityArgs", args)
            .put("targetCharacterId", recipient).put("locationCode", place)
        val agenda = JSONArray()
            .put(item("12:15", "给朋友写信", "send_character_message",
                JSONObject().put("targetCharacterId", friend).put("text", "晚些来聊一聊？"), friend))
            .put(item("15:10", "去庭院转转", "digital_world_action",
                JSONObject().put("worldAction", "visit_public_place")
                    .put("locationCode", DigitalWorldPublicPlaces.COURTYARD),
                place = DigitalWorldPublicPlaces.COURTYARD))
            .put(item("18:30", "分享自己的新发现", "publish_moment",
                JSONObject().put("text", "今天整理了有趣的地图。")))
            .put(item("19:30", "联系没有见过的人", "send_character_message",
                JSONObject().put("targetCharacterId", "resident:not-real")
                    .put("text", "你好"), "resident:not-real"))
        val scheduled = ResidentDailyDecision.saveAgenda(app, person,
            JSONObject().put("agenda", agenda), today)
        assertEquals(3, scheduled.size)
        assertTrue(scheduled.all { it.selfInitiated && it.status == "planned" })
        assertEquals(listOf("send_character_message", "digital_world_action", "publish_moment"),
            scheduled.map { it.activityAction })
        assertEquals(friend, scheduled[0].targetCharacterId)
        assertEquals(DigitalWorldPublicPlaces.COURTYARD, scheduled[1].locationCode)
        assertTrue(RoleDayScheduleStore.plans(app, actor).map { it.id }
            .containsAll(scheduled.map { it.id }))
    }

    @Test(timeout = 60_000) fun dailyWorkerRestoresModelRuntimeWithoutAnActivityOrSilentChatBilling() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        app.getSharedPreferences("lulu_scoped_model_selections_v1", android.content.Context.MODE_PRIVATE)
            .edit().remove("archive_resident_daily").commit()
        ScopedModelSelections.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(ScopedModelSelections, null)
        listOf("connectionStoreInternal", "gatewayInternal").forEach { field ->
            LuluAiServices.javaClass.getDeclaredField(field).apply { isAccessible = true }
                .set(LuluAiServices, null)
        }
        assertTrue(runCatching { LuluAiServices.gateway }.isFailure)
        assertEquals(0, ResidentDailyDecision.tick(app))
        assertNotNull(LuluAiServices.gateway)
        // An absent dedicated selection must still not fall back to Chat.
        assertNull(ScopedModelSelections.selectedArchiveId(ResidentDailyDecision.SCOPE))
    }

    @Test(timeout = 60_000) fun dailyDecisionKeepsIdentityMotivesAndMailAndSavesIndependentReceipts() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        DigitalWorldExpansionStore.initialize(app)
        ResidentSocialMailbox.initialize(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "澄${UUID.randomUUID().toString().take(6)}", identity = "旧地图修复师",
            homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            temperament = "喜欢安静和认真谈论旧地图", createdAt = now.minusSeconds(7 * 86_400)))
        DigitalWorldExpansionStore.commitResidentDuty(native.id, DigitalWorldPublicPlaces.COURTYARD,
            "test-duty", "整理旧地图", now)
        ResidentCharacterBridge.sync(app, now)
        val id = ResidentCharacterBridge.characterId(native.id)
        val peer = MigratedDomainStores.characters.create("朋友${UUID.randomUUID().toString().take(6)}")
        DigitalLifeProfileStore.registerNewLife(peer.characterId, peer.displayName, "数字生命", now.minusSeconds(1))
        assertTrue(DigitalWorldStore.performAction(peer.characterId, "visit_public_place",
            JSONObject().put("locationCode", DigitalWorldPublicPlaces.COURTYARD)).success)
        val letter = ResidentSocialMailbox.send(peer.characterId, id, "下次继续聊旧地图上的河流。", at = now)
        SharedExperienceTimeline.record("own-map-$id", id, "数字世界·工作", native.name,
            "我在整理旧地图，还有一处河流没有核对完。", now, triggerExtraction = false,
            source = "resident-duty", evidenceKind = EventEvidenceKind.Observation)
        CharacterInnerLifeStore.observe(id, "own-map-$id", "尚未核对完旧地图",
            JSONObject().put("motives", JSONArray().put(JSONObject().put("op", "start")
                .put("aim", "想核对旧地图的河流").put("why", "自己还没弄清楚"))), now = now)
        // An unrelated person's private state is not part of this citizen's day.
        val unrelated = MigratedDomainStores.characters.create("旁人${UUID.randomUUID().toString().take(6)}")
        CharacterInnerLifeStore.observe(unrelated.characterId, "private-other", "旁人的私事",
            JSONObject().put("emotion", JSONObject().put("feeling", "OTHER_PRIVATE_SENTINEL")
                .put("cause", "自己的私人经历")), now = now)
        val output = JSONObject().put("lunch", "cafe").put("evening", "courtyard")
            .put("eveningActivity", "读完整本河流图册并与朋友聊一晚")
            .put("thought", "还想接着聊河流的事")
            .put("messageTo", peer.characterId).put("message", "好呀，我们下次接着聊。")
            .put("post", "今天看见旧地图上细细的一条河。")
            .put("innerLife", JSONObject().put("social", JSONObject()
                .put("targetId", peer.characterId).put("interpretation", "他也对旧地图有兴趣")
                .put("reason", "收到他关于河流的来信")))
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", output.toString())))).toString()))
        server.start()
        try {
            // Test this citizen's one decision. Other online characters may
            // legitimately choose their own paid reaction to a public post.
            MigratedDomainStores.characters.settings.value.keys
                .filterNot { it.startsWith("resident:") }
                .forEach { CompanionOnlineStore.resetCharacter(it) }
            val config = LuluAiServices.connectionStore.saveConfiguration(null, "native-test",
                server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runOnce(app, native, now))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(1, server.requestCount)
            val request = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()).toString()
            assertTrue(request.contains("旧地图修复师"))
            assertTrue(request.contains("想核对旧地图的河流"))
            assertTrue(request.contains("下次继续聊旧地图上的河流"))
            assertFalse(request.contains("这是独立跨世界场景"))
            assertFalse(request.contains("OTHER_PRIVATE_SENTINEL"))
            assertTrue(ResidentSocialMailbox.pendingIncoming(id).isEmpty())
            val incomingId = ResidentSocialMailbox.evidenceId(letter, id)
            assertEquals(incomingId, CharacterSocialRelationship.perspective(id, peer.characterId)?.evidenceId)
            assertTrue(CharacterInnerLifeStore.snapshot(id).toString().contains("还想接着聊河流的事"))
            val receipts = SharedExperienceTimeline.all(id).filter { it.source == "tool-router" }
            assertTrue(receipts.any { JSONObject(it.content).optString("tool") == "publish_moment" })
            assertTrue(receipts.any { JSONObject(it.content).optString("tool") == "send_character_message" })
            assertTrue(ResidentSocialMailbox.inbox(peer.characterId).any {
                it.fromId == id && it.content == "好呀，我们下次接着聊。" })
            // The daily plan's prose is an intention, not an executor receipt.
            ResidentDailyDecision.schedule(app)
            val evening = now.atZone(java.time.ZoneId.systemDefault()).toLocalDate()
                .atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant()
            val slot = ResidentDailyRoutine.plan(native, evening)
            assertEquals("读完整本河流图册并与朋友聊一晚", slot.intendedActivity)
            assertFalse(slot.activity.contains("读完整本"))
            ResidentDailyRoutine.refresh(app, evening)
            assertTrue(SharedExperienceTimeline.all(id).any { it.source == "resident-duty" &&
                it.content.contains("具体活动尚待实际执行") })
            SharedExperienceTimeline.deleteEvent(incomingId)
            assertNull(CharacterSocialRelationship.perspective(id, peer.characterId))
            assertFalse(CharacterInnerLifeStore.snapshot(id).toString().contains("还想接着聊河流的事"))
        } finally { server.shutdown() }
    }

    private fun answer(json: String): MockResponse = MockResponse().setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message",
            JSONObject().put("content", json)))).toString())

    @Test fun nestedResidentChoicesMustBeStructurallyValidBeforeAnySideEffect() {
        val at = java.time.LocalDate.now().atTime(8, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant()
        val valid = JSONObject().put("lunch", "home").put("evening", "courtyard")
        val brokenAgenda = JSONObject().put("at", "11:00")
            .put("title", "记下今天的花").put("intent", "想记录生活")
            .put("activityAction", "publish_moment")
            .put("activityArgs", "not-an-object")
        assertTrue(ResidentDailyDecision.dayContractError(
            JSONObject(valid.toString()).put("agenda", JSONArray().put(brokenAgenda)), at)
            .orEmpty().contains("activityArgs"))
        val overLimit = JSONArray()
        repeat(9) { index ->
            overLimit.put(JSONObject().put("at", "12:00")
                .put("title", "活动${index}").put("intent", "今天自己想做")
                .put("activityAction", "publish_moment")
                .put("activityArgs", JSONObject().put("text", "第${index}条")))
        }
        assertTrue(ResidentDailyDecision.dayContractError(
            JSONObject(valid.toString()).put("agenda", overLimit), at)
            .orEmpty().contains("最多"))
        val malformedStep = JSONArray().put(JSONObject().put("tool", "publish_moment")
            .put("args", "not-an-object"))
        assertTrue(ResidentDailyDecision.dueContractError(
            JSONObject().put("choice", "cancel").put("steps", malformedStep), at)
            .orEmpty().contains("args"))
        val wrongDependency = JSONArray().put(JSONObject().put("tool", "publish_moment")
            .put("args", JSONObject().put("text", "只做结构验证"))
            .put("requiresPreviousSuccess", "false"))
        assertTrue(ResidentDailyDecision.dueContractError(
            JSONObject().put("choice", "cancel").put("steps", wrongDependency), at)
            .orEmpty().contains("布尔"))
        assertNull(ResidentDailyDecision.dayContractError(
            JSONObject(valid.toString()).put("agenda", JSONArray()
                .put(JSONObject().put("at", "11:00")
                    .put("title", "记下今天的花").put("intent", "想记录生活")
                    .put("activityAction", "publish_moment")
                    .put("activityArgs", JSONObject().put("text", "今天的花")))), at))
    }

    @Test(timeout = 60_000) fun malformedNestedDailyAgendaIsRepairedInsteadOfSilentlyDropped() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = java.time.LocalDate.now().atTime(8, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "日程字段补正居民${UUID.randomUUID().toString().take(5)}",
            identity = "喜欢记录花草", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        val content = "日程补正后计划分享${UUID.randomUUID()}"
        fun decision(args: Any) = JSONObject().put("lunch", "home")
            .put("evening", "courtyard").put("agenda", JSONArray().put(
                JSONObject().put("at", "11:00")
                    .put("title", "分享花草观察").put("intent", "想记住今天的花")
                    .put("activityAction", "publish_moment")
                    .put("activityArgs", args)))
        val server = MockWebServer()
        server.enqueue(answer(decision("wrong-type").toString()))
        server.enqueue(answer(decision(JSONObject().put("text", content)).toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "nested-agenda-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(2, server.requestCount)
            val actor = ResidentCharacterBridge.characterId(native.id)
            val saved = RoleDayScheduleStore.plans(app, actor)
                .filter { it.activityAction == "publish_moment" &&
                    JSONObject(it.activityArgs).optString("text") == content }
            assertEquals(1, saved.size)
            assertEquals("planned", saved.single().status)
            assertEquals(0, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == content })
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            val day = ResidentDailyDecision.date(now)
            assertTrue(p.getString("invalid:${native.id}:$day", "")
                .orEmpty().contains("wrong-type"))
            assertEquals("finished", p.getString("phase:${native.id}:$day", ""))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun twiceMalformedNestedDailyAgendaNeverSchedulesOrBuysThirdReply() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = java.time.LocalDate.now().atTime(8, 0)
            .atZone(java.time.ZoneId.systemDefault()).toInstant()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "无效日程保护居民${UUID.randomUUID().toString().take(5)}",
            identity = "庭院访客", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        fun invalid(value: String) = JSONObject().put("lunch", "home")
            .put("evening", "courtyard").put("agenda", JSONArray().put(
                JSONObject().put("at", "11:00").put("title", "想发一条动态")
                    .put("intent", "记录今日见闻").put("activityAction", "publish_moment")
                    .put("activityArgs", value)))
        val server = MockWebServer()
        server.enqueue(answer(invalid("broken-first").toString()))
        server.enqueue(answer(invalid("broken-again").toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "nested-twice-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(2, server.requestCount)
            val actor = ResidentCharacterBridge.characterId(native.id)
            assertTrue(RoleDayScheduleStore.plans(app, actor).none {
                it.title == "想发一条动态" })
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            val day = ResidentDailyDecision.date(now)
            assertEquals("invalid_output", p.getString("phase:${native.id}:$day", ""))
            assertFalse(p.contains("decision:${native.id}:$day"))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun malformedNestedDueStepGetsBoundedRepairAndOneReceipt() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "到点字段补正居民${UUID.randomUUID().toString().take(5)}",
            identity = "庭院观察者", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val plan = RoleDayScheduleStore.arrange(app, actor, "分享庭院的花",
            "想记录真实见闻", now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "原计划不应被执行"))
        val content = "修正后的独立动态${UUID.randomUUID()}"
        val first = JSONObject().put("choice", "cancel")
            .put("reason", "今天不执行原计划")
            .put("steps", JSONArray().put(JSONObject()
                .put("tool", "publish_moment").put("args", "broken-args")))
        val corrected = JSONObject().put("choice", "cancel")
            .put("reason", "今天不执行原计划")
            .put("steps", JSONArray().put(JSONObject()
                .put("tool", "publish_moment")
                .put("args", JSONObject().put("text", content))))
        val server = MockWebServer()
        server.enqueue(answer(first.toString()))
        server.enqueue(answer(corrected.toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "nested-due-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(2, server.requestCount)
            assertEquals("cancelled",
                RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == content })
            assertFalse(MomentsStore.posts.value.any {
                it.authorCharacterId == actor && it.content == "原计划不应被执行" })
            val key = "execute:${plan.id}:${plan.dueAt}"
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            assertTrue(p.getString("due-invalid:$key", "")
                .orEmpty().contains("broken-args"))
            assertEquals("finished", p.getString("due-phase:$key", ""))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun malformedDueDecisionIsCorrectedBySameResidentBeforeAnyEffect() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "补正到点居民${UUID.randomUUID().toString().take(5)}",
            identity = "庭院观察者", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val text = "补正后本人选择分享-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "整理庭院笔记", "自己想记录",
            now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "旧活动未被执行"))
        val repaired = JSONObject().put("choice", "cancel").put("reason", "今天先不整理")
            .put("steps", JSONArray().put(JSONObject().put("tool", "publish_moment")
                .put("args", JSONObject().put("text", text))))
        val server = MockWebServer()
        server.enqueue(answer(JSONObject().put("choice", "reschedule")
            .put("dueAt", "not-an-ISO-time").put("reason", "我想改期").toString()))
        server.enqueue(answer(repaired.toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "repair-due-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(2, server.requestCount)
            assertEquals("cancelled",
                RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == text
            })
            val key = "execute:${plan.id}:${plan.dueAt}"
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            assertTrue(p.getString("due-invalid:$key", "").orEmpty().contains("not-an-ISO-time"))
            assertEquals("finished", p.getString("due-phase:$key", ""))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun secondMalformedDueResponseStopsWithoutChargingAgainOrExecuting() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "无效结构居民${UUID.randomUUID().toString().take(5)}",
            identity = "园艺爱好者", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val plan = RoleDayScheduleStore.arrange(app, actor, "观察花草", "自己想看看",
            now.minusSeconds(120), now.minusSeconds(600),
            activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "绝不能自动发送"))
        val server = MockWebServer()
        server.enqueue(answer("not-json"))
        server.enqueue(answer(JSONObject().put("choice", "reschedule")
            .put("dueAt", "still-invalid").toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "repair-fail-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(2, server.requestCount)
            val key = "execute:${plan.id}:${plan.dueAt}"
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            assertEquals("invalid_output", p.getString("due-phase:$key", ""))
            assertFalse(p.contains("due-decision:$key"))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(2, server.requestCount)
            assertEquals("planned",
                RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun dailyMalformedOutputGetsOneBoundedRepairAndPersistsOriginal() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "补正日程居民${UUID.randomUUID().toString().take(5)}",
            identity = "庭院居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        val server = MockWebServer()
        server.enqueue(answer(JSONObject().put("lunch", "unknown-place")
            .put("evening", "home").put("agenda", JSONArray()).toString()))
        server.enqueue(answer(JSONObject().put("lunch", "cafe")
            .put("evening", "home").put("agenda", JSONArray())
            .put("thought", "我今天想去咖啡馆坐坐").toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(null,
                "repair-day-${UUID.randomUUID()}", server.url("/").toString(), "test")
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
            ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            assertTrue(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(2, server.requestCount)
            assertEquals("cafe", ResidentDailyDecision.planFor(app, native.id, now)?.optString("lunch"))
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            assertTrue(p.getString("invalid:${native.id}:${ResidentDailyDecision.date(now)}", "")
                .orEmpty().contains("unknown-place"))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun explicitDueHttpRejectionRetriesOnlyAfterConnectionChanges() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "拒绝请求居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val originalText = "不得擅自发布-${UUID.randomUUID()}"
        val sideText = "修复连接后独立分享-${UUID.randomUUID()}"
        val plan = RoleDayScheduleStore.arrange(app, actor, "整理花草", "今天想记笔记",
            now.minusSeconds(120), now.minusSeconds(600), activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", originalText))
        val rejectedServer = MockWebServer()
        rejectedServer.enqueue(MockResponse().setResponseCode(401)
            .setBody("""{"error":{"message":"unauthorized"}}"""))
        rejectedServer.start()
        val repairedServer = MockWebServer()
        repairedServer.enqueue(answer(JSONObject().put("choice", "cancel")
            .put("reason", "暂时不整理").put("steps", JSONArray()
                .put(JSONObject().put("tool", "publish_moment")
                    .put("args", JSONObject().put("text", sideText)))).toString()))
        repairedServer.start()
        try {
            fun select(server: MockWebServer): String {
                val config = LuluAiServices.connectionStore.saveConfiguration(null,
                    "rejected-due-${UUID.randomUUID()}", server.url("/").toString(), "test")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
                return archive.id
            }
            select(rejectedServer)
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            val key = "execute:${plan.id}:${plan.dueAt}"
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            assertEquals("definite_rejected", p.getString("due-phase:$key", ""))
            assertFalse(ResidentDailyDecision.hasSafeDueRequestToRetry(app, plan))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(1, rejectedServer.requestCount)
            assertFalse(MomentsStore.posts.value.any {
                it.authorCharacterId == actor && it.content == originalText })
            select(repairedServer)
            assertTrue(ResidentDailyDecision.hasSafeDueRequestToRetry(app, plan))
            assertTrue(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals("cancelled",
                RoleDayScheduleStore.plans(app, actor).single { it.id == plan.id }.status)
            assertEquals(1, repairedServer.requestCount)
            assertEquals(1, MomentsStore.posts.value.count {
                it.authorCharacterId == actor && it.content == sideText })
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(1, repairedServer.requestCount)
        } finally {
            rejectedServer.shutdown()
            repairedServer.shutdown()
        }
    }

    @Test(timeout = 60_000) fun dailyHttpRejectionRecoversOnceWithoutRebuyingOldRequest() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "每日请求居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        val oldServer = MockWebServer()
        oldServer.enqueue(MockResponse().setResponseCode(413)
            .setBody("request too large"))
        oldServer.start()
        val newServer = MockWebServer()
        newServer.enqueue(answer(JSONObject().put("lunch", "home")
            .put("evening", "cafe").put("agenda", JSONArray()).put("steps", JSONArray())
            .put("post", "修复配置后的日记-${UUID.randomUUID()}").toString()))
        newServer.start()
        try {
            fun select(server: MockWebServer) {
                val config = LuluAiServices.connectionStore.saveConfiguration(null,
                    "rejected-day-${UUID.randomUUID()}", server.url("/").toString(), "test")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            }
            select(oldServer)
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            val phase = "phase:${native.id}:${ResidentDailyDecision.date(now)}"
            assertEquals("definite_rejected", p.getString(phase, ""))
            assertFalse(ResidentDailyDecision.hasSafeDayRequestToRetry(app, native.id, now))
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(1, oldServer.requestCount)
            select(newServer)
            assertTrue(ResidentDailyDecision.hasSafeDayRequestToRetry(app, native.id, now))
            assertTrue(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals("finished", p.getString(phase, ""))
            assertEquals(1, newServer.requestCount)
            assertFalse(ResidentDailyDecision.runOnce(app, native, now))
            assertEquals(1, newServer.requestCount)
        } finally {
            oldServer.shutdown()
            newServer.shutdown()
        }
    }

    @Test(timeout = 60_000) fun unknownChargeHttpFailureNeverRetriesAfterConnectionChange() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as android.content.Context
        initializeBackgroundRuntime(app)
        val now = Instant.now()
        val native = DigitalWorldExpansionStore.registerResident(DigitalWorldResident(
            name = "未知结果居民${UUID.randomUUID().toString().take(5)}",
            identity = "居民", homeLocationCode = DigitalWorldPublicPlaces.COURTYARD,
            createdAt = now.minusSeconds(3600)))
        ResidentCharacterBridge.sync(app, now)
        val actor = ResidentCharacterBridge.characterId(native.id)
        val plan = RoleDayScheduleStore.arrange(app, actor, "去庭院散步", "想看看花",
            now.minusSeconds(120), now.minusSeconds(600), activityAction = "publish_moment",
            activityArgs = JSONObject().put("text", "绝不能盲目发布"))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(502).setBody("gateway failure"))
        server.start()
        val alternate = MockWebServer()
        alternate.start()
        try {
            fun select(target: MockWebServer) {
                val config = LuluAiServices.connectionStore.saveConfiguration(null,
                    "uncertain-due-${UUID.randomUUID()}", target.url("/").toString(), "test")
                val archive = LuluAiServices.connectionStore.addArchive(config.id, "test")
                ScopedModelSelections.select(ResidentDailyDecision.SCOPE, archive.id)
            }
            select(server)
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            val key = "execute:${plan.id}:${plan.dueAt}"
            val p = app.getSharedPreferences("lulu_resident_daily_decisions_v1",
                android.content.Context.MODE_PRIVATE)
            assertEquals("uncertain", p.getString("due-phase:$key", ""))
            select(alternate)
            assertFalse(ResidentDailyDecision.hasSafeDueRequestToRetry(app, plan))
            assertFalse(ResidentDailyDecision.runDuePlan(app, plan, now))
            assertEquals(0, alternate.requestCount)
            assertFalse(MomentsStore.posts.value.any {
                it.authorCharacterId == actor && it.content == "绝不能盲目发布" })
        } finally {
            server.shutdown()
            alternate.shutdown()
        }
    }
}
