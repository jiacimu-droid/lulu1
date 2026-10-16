package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryPolicy
import com.jiacimu.lulu.core.MemoryTier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryEvidenceCommitIntegrationTest {
    private val role = "memory-lifecycle"
    private val now = Instant.now()
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    private fun initialize(server: MockWebServer? = null): Context {
        val context = RuntimeEnvironment.getApplication() as Context
        if (server != null) context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
            .putString("memory_extract_url", server.url("/").toString())
            .putString("memory_extract_key", "test-only").putString("memory_extract_model", "test-memory").commit()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        MemoryValidityStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(MemoryValidityStore, null)
        MemoryValidityStore.initialize(context)
        return context
    }
    private fun event(id: String, content: String, user: Boolean = true, at: Instant = now) =
        SharedExperienceTimeline.record(id, role, "私聊", if (user) "用户" else "角色", content, at,
            triggerExtraction = false, evidenceKind = if (user) EventEvidenceKind.UserStatement else EventEvidenceKind.CharacterStatement)
    private fun entry(id: String, text: String, source: String = "手动") =
        MemoryEntry(id, role, text, MemoryKind.Fact, source, now, now, 7, false, true)
    private fun reply(content: String, kind: String = "Fact", source: String = "target", supersedes: String? = null): MockResponse {
        val item = JSONObject().put("kind", kind).put("content", content).put("sourceEventIds", JSONArray().put(source))
        supersedes?.let { item.put("supersedes", JSONArray().put(it)) }
        return MockResponse().setHeader("Content-Type", "application/json").setBody(JSONObject().put("choices", JSONArray().put(
            JSONObject().put("message", JSONObject().put("content", JSONArray().put(item).toString())).put("finish_reason", "stop"))).toString())
    }

    @Test fun editedSourceInvalidatesSummaryBeforeRecordReturns() = runBlocking {
        initialize()
        event("target", "原先的相处说明")
        LuluRepositories.memory.save(entry("derived", "旧的理解", "timeline-events:target"))
        event("target", "已经修改后的相处说明")
        assertTrue(LuluRepositories.memory.snapshot(role).isEmpty())
        assertEquals("已经修改后的相处说明", SharedExperienceTimeline.all(role).single().content)
        assertTrue(LocalMemoryRepository().apply { initialize(RuntimeEnvironment.getApplication()) }.snapshot(role).isEmpty())
    }

    @Test(timeout = 60000) fun changingAnUncitedNeighbourRejectsImmediateResultAndRetriesAfterRestart() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            val context = initialize(server)
            event("target", "以后称呼我小辞")
            event("neighbour", "原先的附近说明", user = false, at = now.plusSeconds(1))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    event("neighbour", "这句话的语境已经更改", user = false, at = now.plusSeconds(1))
                    return reply("旧语境下的错误理解")
                }
            }
            assertTrue(runCatching { LuluRepositories.memory.summarizeNow(role) }.isFailure)
            assertTrue(LuluRepositories.memory.snapshot(role).isEmpty())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = reply("用户希望被称为小辞")
            }
            val restored = LocalMemoryRepository().apply { initialize(context) }
            restored.summarizeNow(role)
            assertEquals("用户希望被称为小辞", restored.snapshot(role).single().content)
            restored.summarizeNow(role)
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60000) fun changingBatchContextDoesNotAdvanceTheCheckpointOfUnchangedMessages() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            initialize(server)
            LuluRepositories.memory.updatePersonalPolicy(role, MemoryPolicy(excludedRecentMessages = 0, readableThreshold = 2))
            event("target", "今天在桥上见到一只橘猫")
            event("neighbour", "橘猫在桥东", user = false, at = now.plusSeconds(1))
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    event("neighbour", "橘猫其实是在桥西", user = false, at = now.plusSeconds(1))
                    return reply("桥东的橘猫", "Timeline")
                }
            }
            assertTrue(runCatching { LuluRepositories.memory.summarizeNow(role) }.isFailure)
            assertEquals(2, LuluRepositories.memory.pendingMessageCount(role))
            assertTrue(LuluRepositories.memory.snapshot(role).isEmpty())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = reply("桥西的橘猫", "Timeline")
            }
            LuluRepositories.memory.summarizeNow(role)
            assertEquals(0, LuluRepositories.memory.pendingMessageCount(role))
            assertTrue(LuluRepositories.memory.snapshot(role).single().content.contains("桥西"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test(timeout = 60000) fun semanticCorrectionRetiresOldFactInTheExistingExtractionRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(reply("用户现在只选淡茶，浓茶会让她难受", supersedes = "old-tea"))
        server.start()
        try {
            val context = initialize(server)
            LuluRepositories.memory.save(entry("old-tea", "用户喜欢浓茶").copy(tier = MemoryTier.Core))
            event("target", "以后只喝淡茶了，浓茶会让我难受")
            LuluRepositories.memory.summarizeNow(role)
            assertFalse(MemoryValidityStore.isActive("old-tea"))
            assertEquals(2, LuluRepositories.memory.snapshot(role).size) // History is retained.
            assertFalse(CoreMemoryDelivery.active(role).any { it.id == "old-tea" })
            val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(request.contains("memoryId=old-tea"))
            assertEquals(1, server.requestCount) // No separate correction/classification call.
            MemoryValidityStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(MemoryValidityStore, null)
            MemoryValidityStore.initialize(context)
            assertFalse(MemoryValidityStore.isActive("old-tea"))
        } finally { server.shutdown() }
    }

    @Test fun sourceSnapshotChecksAllIdsBeyondTheSqliteChunkSizeAndDeletion() {
        initialize()
        repeat(135) { event("source-$it", "事实$it", at = now.plusSeconds(it.toLong())) }
        val snapshot = SharedExperienceTimeline.all(role)
        assertEquals(135, SharedExperienceTimeline.eventsByIds(role, snapshot.map { it.id }).size)
        var committed = false
        assertTrue(SharedExperienceTimeline.commitIfEvidenceUnchanged(role, snapshot) { committed = true })
        assertTrue(committed)
        SharedExperienceTimeline.deleteEvent("source-134")
        assertFalse(SharedExperienceTimeline.commitIfEvidenceUnchanged(role, snapshot) { fail("Cannot accept deleted input") })
    }

    @Test fun memoryIsRetainedWhenItsRawSourceWasConsideredButDidNotFitThePrompt() = runBlocking {
        initialize()
        event("target", "橘猫在桥西等我" + "一段当时的原话。".repeat(70))
        LuluRepositories.memory.save(entry("cat", "橘猫在桥西等我", "timeline-events:target"))
        val assembled = UnifiedMemoryOrchestrator.assemble(role, UnifiedMemoryRequest(currentInput = "橘猫在桥西"),
            recentCharacterBudget = 0, evidenceCharacterBudget = 0, localOnly = false)
        assertTrue(assembled.memories.any { it.id == "cat" })
        assertTrue(assembled.sourceEvidence.isBlank())
        val full = UnifiedMemoryOrchestrator.assemble(role, UnifiedMemoryRequest(currentInput = "橘猫在桥西"),
            recentCharacterBudget = 3000, evidenceCharacterBudget = 0, localOnly = false)
        val working = MemoryWorkingSet.assemble(full, emptyList(), emptyList(),
            PromptContextBudget(18, 4000, 0, 3000, false, false, 0, false), "")
        assertFalse("memory:cat" in working.selected)
        assertTrue(working.sections.values.any { it.contains("橘猫在桥西等我") })
    }
}
