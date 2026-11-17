package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.core.MemoryPolicy
import java.time.Instant
import kotlinx.coroutines.flow.first
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

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SemanticMemoryTurnPipelineTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }
    private val role = "semantic-pipeline-role"

    private fun initialize(server: MockWebServer? = null): Context {
        val app = RuntimeEnvironment.getApplication() as Context
        if (server != null) {
            app.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", server.url("/").toString())
                .putString("memory_extract_key", "test-only")
                .putString("memory_extract_model", "test-memory").commit()
        }
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        LuluAiServices.initialize(app)
        return app
    }

    private fun record20TurnsWith120GameActions() {
        val at = Instant.parse("2026-11-17T00:00:00Z")
        SharedExperienceTimeline.record(
            "talk-0", role, "私聊", "主人", "十分钟前我们约好了一起冒险",
            at, false, sessionId = "dm-1", source = "message",
            evidenceKind = EventEvidenceKind.UserStatement)
        repeat(120) { i ->
            SharedExperienceTimeline.record(
                "relic:run:$role:$i", role, "共同游戏《迷雾摸金》", "游戏执行器",
                "第${i}次动作实际完成", at.plusSeconds(i + 1L), false,
                source = "game-relic", evidenceKind = EventEvidenceKind.ToolResult)
        }
        // Actual back-and-forth speech: 19 turns, not 19 raw gameplay ticks.
        (1..19).forEach { index ->
            SharedExperienceTimeline.record(
                "talk-$index", role, "私聊",
                if (index % 2 == 0) "主人" else "江渡",
                if (index == 19) "后来继续聊刚才的冒险" else "对话第 $index 轮",
                at.plusSeconds(150 + index.toLong()), false,
                sessionId = "dm-1", source = "message",
                evidenceKind = if (index % 2 == 0)
                    EventEvidenceKind.UserStatement else EventEvidenceKind.CharacterStatement)
        }
    }

    @Test fun sameTwentyTurnWindowControlsRecallProtectionAndPendingExtraction() {
        initialize()
        record20TurnsWith120GameActions()
        val repository = LuluRepositories.memory
        val turns = repository.contextTimelineTurns(role)
        assertEquals(MemoryPolicy(5, 15).rawContextMessageCount, turns.size)
        assertEquals(1, turns.count { it.key == "game:relic_escape:run" })
        assertEquals(120, turns.single { it.key == "game:relic_escape:run" }.events.size)
        assertEquals(15, repository.pendingMessageCount(role))
        val pending = repository.pendingTimelineEvents(role)
        assertEquals(134, pending.size) // 14 speech source rows plus 120 game receipts
        assertTrue(pending.any { it.id == "talk-0" })
        assertFalse(pending.any { it.id == "talk-19" })
        val view = MemoryExtractionTurnView.build(turns.take(15))
        assertEquals(15, view.turnCount)
        assertEquals(134, view.rawEventCount)
        assertTrue(view.visibleSources.size < view.rawEventCount)
        assertTrue(view.text.contains("十分钟前我们约好了一起冒险"))
        assertTrue(view.text.contains("真实事件"))
        assertTrue(view.visibleSources.all { event ->
            view.text.contains("eventId=${event.id}@${event.revision}")
        })
    }

    @Test(timeout = 60000) fun onePaidExtractionHandlesTheSameFifteenRealTurnsWithGameBurst() = runBlocking {
        val server = MockWebServer()
        val memory = JSONObject().put("kind", "Timeline")
            .put("content", "与主人一起探索迷雾摸金，并约定继续冒险")
            .put("sourceEventIds", JSONArray().put("talk-0"))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            JSONObject().put("choices", JSONArray().put(JSONObject().put(
                "message", JSONObject().put("content", JSONArray().put(memory).toString()))
                .put("finish_reason", "stop"))).toString()))
        server.start()
        try {
            initialize(server)
            record20TurnsWith120GameActions()
            val repository = LuluRepositories.memory
            assertEquals(15, repository.pendingMessageCount(role))
            repository.summarizeNow(role)
            assertEquals(1, server.requestCount)
            assertEquals(0, repository.pendingMessageCount(role))
            assertEquals(1, repository.snapshot(role).size)
            assertEquals(listOf("talk-0"), repository.snapshot(role).single().sourceIds())
            val request = server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8()
            assertTrue(request.contains("eventId=talk-0@"))
            assertTrue(request.contains("同一经历"))
            assertFalse(request.contains("eventId=talk-19@")) // protected tail
            assertEquals(20, repository.contextTimelineTurns(role).size)
            // The protected tail is not accidentally certified as processed.
            assertTrue(repository.hasUnprocessedEvents(role))
        } finally { server.shutdown() }
    }

    @Test fun oldEventCountSettingsMigrateOnceAndNewCustomTurnsPersist() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        app.getSharedPreferences("lulu_memory_store", Context.MODE_PRIVATE).edit()
            .putString("state_v1", JSONObject().put("globalPolicy", JSONObject()
                .put("excludedRecentMessages", 25).put("readableThreshold", 20)
                .put("autoSummarize", false)).toString()).commit()
        val repository = LocalMemoryRepository().apply { initialize(app) }
        assertEquals(MemoryPolicy(5, 15, false), repository.observePolicy(role).first())
        repository.updatePersonalPolicy(role, MemoryPolicy(4, 16, true))
        val reopened = LocalMemoryRepository().apply { initialize(app) }
        assertEquals(MemoryPolicy(4, 16, true), reopened.observePolicy(role).first())
        assertEquals(MemoryPolicy(5, 15, false), reopened.observePolicy("another-role").first())
    }
}
