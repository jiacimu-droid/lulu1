package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.*
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.mockwebserver.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MemoryLifecycleIntegrationTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }
    private fun initialize(server: MockWebServer? = null): Context {
        val context = RuntimeEnvironment.getApplication() as Context
        server?.let {
            context.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", it.url("/").toString()).putString("memory_extract_key", "test-only")
                .putString("memory_extract_model", "test").commit()
        }
        LuluRepositories.initialize(context)
        LuluRepositories.lexicon.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        return context
    }
    private fun event(text: String, id: String = "source") = SharedExperienceTimeline.record(id, "lifecycle", "私聊", "用户", text,
        triggerExtraction = false, evidenceKind = EventEvidenceKind.UserStatement)
    private fun memory(id: String, text: String, revision: Long = 1) = MemoryEntry(id, "lifecycle", text,
        MemoryKind.Fact, "timeline-events:source", null, Instant.now(), 5, false, true,
        sourceRevisions = mapOf("source" to revision))
    private fun reply(content: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", content))
            .put("finish_reason", "stop"))).toString())

    @Test fun coalescedQueuePreservesNewArrivalsBackoffAndRestartWatermark() {
        val context = initialize()
        val first = MemoryExtractionJobStore.enqueue("lifecycle", sourceReplyId = "first")
        MemoryExtractionJobStore.failed(first.id, IllegalStateException("offline"))
        val second = MemoryExtractionJobStore.enqueue("lifecycle", sourceReplyId = "second")
        MemoryExtractionJobStore.complete(first.id, first.revision)
        assertEquals(1, MemoryExtractionJobStore.pending().size)
        assertFalse(memoryExtractionRetryDue(second))
        MemoryExtractionJobStore.removeBySourceEvent("second")
        MemoryTestEnvironment.release()
        MemoryExtractionJobStore.initialize(context)
        assertEquals(second.revision, MemoryExtractionJobStore.pending().single().revision)
        assertEquals(1, MemoryExtractionJobStore.pending().single().attempts)
        MemoryExtractionJobStore.complete(second.id, second.revision)
        assertTrue(MemoryExtractionJobStore.pending().isEmpty())
    }

    @Test fun sourceEditRetractsMemoryAndConcernAndRejectsLateOldVersions() = runBlocking {
        initialize()
        event("考试还没结束")
        val old = memory("old", "考试还没结束")
        LuluRepositories.memory.upsert(old)
        val now = Instant.now()
        val concern = LexiconEntry("concern", "lifecycle", LexiconSection.Concern, "考试", "想问问考试结果", null, now, now,
            sourceEventIds = listOf("source"), sourceRevisions = mapOf("source" to 1L))
        LuluRepositories.lexicon.save(concern)
        event("考试已经结束")
        assertTrue(LuluRepositories.memory.snapshot("lifecycle").isEmpty())
        assertTrue(LuluRepositories.lexicon.snapshot("lifecycle").isEmpty())
        LuluRepositories.memory.upsert(old)
        assertFalse(LuluRepositories.lexicon.saveDerived(concern, null))
        assertTrue(LuluRepositories.memory.snapshot("lifecycle").isEmpty())
        LuluRepositories.memory.upsert(memory("new", "考试已经结束", 2))
        SharedExperienceTimeline.deleteEvent("source")
        assertTrue(LuluRepositories.memory.snapshot("lifecycle").isEmpty())
        LuluRepositories.memory.upsert(memory("late", "考试已经结束", 2))
        assertTrue(LuluRepositories.memory.snapshot("lifecycle").isEmpty())
    }

    @Test fun extractionCannotCommitAnAnswerReadBeforeASourceEdit() = runBlocking {
        val server = MockWebServer()
        val release = CountDownLatch(1)
        var count = 0
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                count++
                if (count == 1) {
                    check(release.await(10, TimeUnit.SECONDS))
                    return reply("""[{"kind":"Fact","content":"住在成都","sourceEventIds":["source"],"supersedes":[]}]""")
                }
                return reply("[]")
            }
        }
        server.start()
        try {
            initialize(server)
            LuluRepositories.memory.updatePolicy("lifecycle", MemoryPolicy(0, 1))
            event("我住在成都")
            val extraction = async(Dispatchers.IO) { LuluRepositories.memory.summarizeNow("lifecycle") }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            event("我住在杭州")
            release.countDown()
            withTimeout(15_000) { extraction.await() }
            assertTrue(LuluRepositories.memory.snapshot("lifecycle").isEmpty())
            assertEquals(2, server.requestCount)
        } finally { release.countDown(); server.shutdown() }
    }

    @Test fun sameExtractionUpdatesCurrentUnderstandingAndArchivesTheOldFact() = runBlocking {
        val server = MockWebServer()
        server.enqueue(reply("""[{"kind":"Fact","content":"用户现在喜欢清茶","sourceEventIds":["source"],"tier":"Core","supersedes":["prior"]}]"""))
        server.start()
        try {
            val context = initialize(server)
            val prior = memory("prior", "用户喜欢咖啡").copy(source = "手动", sourceRevisions = emptyMap(), pinned = true)
            LuluRepositories.memory.save(prior)
            event("记住，我现在改成喜欢清茶了")
            LuluRepositories.memory.summarizeNow("lifecycle")
            assertFalse(MemoryValidityStore.isActive("prior"))
            assertEquals(2, LuluRepositories.memory.observeMemories("lifecycle").first().size)
            assertTrue(CoreMemoryDelivery.active("lifecycle").none { it.id == "prior" })
            val restored = LocalMemoryRepository().apply { initialize(context) }
            assertEquals(mapOf("source" to 1L), restored.snapshot("lifecycle").first { it.id != "prior" }.sourceRevisions)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
