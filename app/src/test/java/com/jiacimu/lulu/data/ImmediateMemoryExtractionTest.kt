package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryTier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ImmediateMemoryExtractionTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }
    @Test fun residentImportantMemoryAtZeroQuotaDefersWithoutNetworkAndKeepsSource() = runBlocking {
        val ctx = RuntimeEnvironment.getApplication() as Context
        val settings = ctx.getSharedPreferences(
            "lulu_resident_activity_policy_v1", Context.MODE_PRIVATE)
        settings.edit().clear().commit()
        try {
            assertTrue(ResidentDailyPolicy.setMaxModelDecisions(ctx, 0))
            LuluRepositories.initialize(ctx)
            SharedExperienceTimeline.initialize(ctx)
            val residentId = "resident:memory-at-quota"
            val eventId = "resident-quota-memory-source"
            SharedExperienceTimeline.record(eventId, residentId, "私聊", "用户",
                "你记住，以后别把我叫作那个旧称呼。",
                triggerExtraction = false,
                evidenceKind = EventEvidenceKind.UserStatement)
            val result = runCatching { LuluRepositories.memory.summarizeNow(residentId) }
            val paused = result.exceptionOrNull()
            assertTrue("Budget deferral is a distinct pending outcome, not a successful summary",
                paused is ResidentMemoryBudgetDeferred)
            assertTrue((paused as ResidentMemoryBudgetDeferred).resumeAt.isAfter(java.time.Instant.now()))
            assertTrue(LuluRepositories.memory.hasUnprocessedEvents(residentId))
            assertTrue(LuluRepositories.memory.snapshot(residentId).isEmpty())
            assertEquals(0, ResidentDailyPolicy.modelDecisionsUsed(ctx, residentId))
            // Direct owner-triggered memory maintenance also stays honest:
            // the deferred job persists and does not claim an empty success.
            LuluRepositories.memory.flushNow(residentId)
            val queued = MemoryExtractionJobStore.pending()
                .single { it.characterId == residentId }
            assertNotNull(queued.deferredUntil)
            assertFalse(memoryExtractionRetryDue(queued))
            assertEquals(0, queued.attempts)
            assertTrue(LuluRepositories.memory.hasUnprocessedEvents(residentId))
        } finally {
            settings.edit().clear().commit()
        }
    }

    @Test fun importantFirstMessageDefersDuplicatePaidRetryThenPersistsAfterCooldown() = runBlocking {
        val server = MockWebServer()
        val eventId = "important-first-" + java.util.UUID.randomUUID()
        val roleId = "important-memory-" + java.util.UUID.randomUUID()
        val result = JSONArray().put(JSONObject().put("kind", "Fact").put("content", "用户要求以后不要用旧昵称称呼她")
            .put("sourceEventIds", JSONArray().put(eventId)).put("tier", "Core").put("strength", 10))
        val body = JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", result.toString())).put("finish_reason", "stop"))).toString()
        server.enqueue(MockResponse().setResponseCode(503).setBody("temporary failure"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(body))
        server.start()
        try {
            val context = RuntimeEnvironment.getApplication() as Context
            val budgetPrefs = context.getSharedPreferences(
                "lulu_background_model_requests_v1", Context.MODE_PRIVATE)
            val existingAttempts = budgetPrefs.all.keys.filter {
                it.startsWith("evidence-attempt:")
            }.toSet()
            context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", server.url("/").toString())
                .putString("memory_extract_key", "test-only").putString("memory_extract_model", "test-memory").commit()
            LuluRepositories.initialize(context)
            SharedExperienceTimeline.initialize(context)
            LuluAiServices.initialize(context)
            SharedExperienceTimeline.record(eventId, roleId, "私聊", "用户", "你记住，以后不要再用旧昵称叫我",
                triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement)
            assertTrue(runCatching { LuluRepositories.memory.summarizeNow(roleId) }.isFailure)
            assertTrue(LuluRepositories.memory.snapshot(roleId).isEmpty())
            assertTrue(LuluRepositories.memory.hasUnprocessedEvents(roleId))
            assertEquals(1, server.requestCount)

            // HTTP 503 is not proof the provider was never billed. The same
            // evidence cannot be sent again immediately, even on manual retry.
            val early = runCatching { LuluRepositories.memory.summarizeNow(roleId) }
            assertTrue("An immediate duplicate must retain its pending source",
                early.exceptionOrNull() is BackgroundModelBudgetDeferred)
            assertEquals(1, server.requestCount)
            assertTrue(LuluRepositories.memory.hasUnprocessedEvents(roleId))

            // Advance only this test's persisted handoff timestamp: production
            // must continue enforcing the full cooldown and never replay POSTs.
            val newAttempts = budgetPrefs.all.keys.filter {
                it.startsWith("evidence-attempt:")
            }.toSet() - existingAttempts
            assertEquals(1, newAttempts.size)
            assertTrue(budgetPrefs.edit().putLong(newAttempts.single(),
                System.currentTimeMillis() - BackgroundModelRequestBudget.SAME_EVIDENCE_RETRY_MS - 1_000L).commit())
            LuluRepositories.memory.summarizeNow(roleId)
            assertEquals(MemoryTier.Core, LuluRepositories.memory.snapshot(roleId).single().tier)
            assertEquals(2, server.requestCount)
            repeat(2) { assertEquals("/chat/completions", server.takeRequest(5, TimeUnit.SECONDS)?.path) }
            val restored = LocalMemoryRepository().apply { initialize(context) }
            restored.summarizeNow(roleId)
            assertEquals(2, server.requestCount) // Successful immediate checkpoint survives restart.
            assertEquals(MemoryTier.Core, restored.snapshot(roleId).single().tier)
        } finally { server.shutdown() }
    }
}
