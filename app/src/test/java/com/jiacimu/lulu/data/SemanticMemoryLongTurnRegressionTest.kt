package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import java.time.Instant
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
class SemanticMemoryLongTurnRegressionTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    private val at = Instant.parse("2026-11-17T00:00:00Z")
    private val role = "semantic-long-turns"

    private fun event(i: Int, text: String) = SharedTimelineEvent(
        id = "speech-$i", characterId = role, channel = "私聊",
        speaker = if (i % 2 == 0) "主人" else "江渡",
        content = text, occurredAt = at.plusSeconds(i.toLong()),
        sessionId = "same-chat", source = "message",
        evidenceKind = if (i % 2 == 0) EventEvidenceKind.UserStatement
            else EventEvidenceKind.CharacterStatement,
    )

    @Test fun completeLongUserMessageAndItsEndingRemainVisibleAndCitable() {
        val long = "语音正文开始" + "这是一段用户亲口说的具体经历".repeat(650) +
            "末尾真正重要的细节：我后来决定不再改变约定"
        val raw = event(0, long)
        val turns = RecentSemanticTimeline.select(listOf(raw), 20)
        val view = MemoryExtractionTurnView.build(MemoryExtractionTurnView.fit(turns))
        assertEquals(1, view.turnCount)
        assertEquals(listOf("speech-0"), view.visibleSources.map { it.id })
        assertTrue(view.text.contains("eventId=speech-0@"))
        assertTrue(view.text.contains("语音正文开始"))
        assertTrue(view.text.contains("末尾真正重要的细节：我后来决定不再改变约定"))
        assertTrue(view.text.contains(long))
    }

    @Test fun boundedFitNeverSplitsARealReplyOrSilentlyDiscardsAnUnseenTail() {
        val messages = (0 until 15).map { i ->
            event(i, "第${i}轮 " + "完整发言内容".repeat(900) + "本轮末尾具体事项-$i")
        }
        val turns = RecentSemanticTimeline.select(messages, 35)
        assertEquals(15, turns.size)
        val first = MemoryExtractionTurnView.fit(turns)
        assertTrue("A large batch needs bounded subrequests", first.size in 1..14)
        val second = MemoryExtractionTurnView.fit(turns.drop(first.size))
        assertTrue(second.isNotEmpty())
        val all = (first + second)
        assertEquals(15, all.size)
        assertEquals(messages.map { it.id }.toSet(),
            all.flatMap { turn -> turn.events.map { it.id } }.toSet())
        listOf(first, second).forEach { batch ->
            val view = MemoryExtractionTurnView.build(batch)
            batch.forEach { turn ->
                turn.events.forEach { original ->
                    assertTrue(view.text.contains(original.evidenceContent))
                    assertTrue(view.visibleSources.any { it.id == original.id })
                }
            }
        }
    }

    @Test fun oversizeSingleMessageFailsClosedRatherThanSavingAFalseCheckpoint() {
        val oversized = event(0, "原始完整讲话".repeat(8_000))
        val turns = RecentSemanticTimeline.select(listOf(oversized), 20)
        val error = runCatching { MemoryExtractionTurnView.fit(turns) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
        assertTrue(error!!.message.orEmpty().contains("未标记整理完成"))
    }

    @Test(timeout = 60000) fun triggeredFifteenTurnsDrainAcrossTwoFullTextRequestsWithoutTouchingFiveProtectedTurns() = runBlocking {
        val server = MockWebServer()
        val emptyReply = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", "[]"))
            .put("finish_reason", "stop"))).toString()
        repeat(4) {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody(emptyReply))
        }
        server.start()
        try {
            val app = RuntimeEnvironment.getApplication() as Context
            app.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", server.url("/").toString())
                .putString("memory_extract_key", "test-only")
                .putString("memory_extract_model", "test-memory").commit()
            LuluRepositories.initialize(app)
            SharedExperienceTimeline.initialize(app)
            LuluAiServices.initialize(app)
            repeat(20) { i ->
                val raw = event(i, if (i < 15)
                    "第${i}轮主人和角色聊天 " + "一段完整的话".repeat(900) + "收尾细节-$i"
                    else "需要保护的最新发言-$i")
                SharedExperienceTimeline.record(raw.id, role, raw.channel,
                    raw.speaker, raw.content, raw.occurredAt,
                    triggerExtraction = false, sessionId = raw.sessionId,
                    source = raw.source, evidenceKind = raw.evidenceKind)
            }
            assertEquals(15, LuluRepositories.memory.pendingMessageCount(role))
            LuluRepositories.memory.summarizeNow(role)
            assertEquals(0, LuluRepositories.memory.pendingMessageCount(role))
            assertEquals(2, server.requestCount)
            val first = server.takeRequest().body.readUtf8()
            val second = server.takeRequest().body.readUtf8()
            val together = first + second
            assertTrue(together.contains("收尾细节-0"))
            assertTrue(together.contains("收尾细节-14"))
            assertFalse(together.contains("需要保护的最新发言-19"))
            assertTrue(LuluRepositories.memory.hasUnprocessedEvents(role))
        } finally { server.shutdown() }
    }
    @Test fun oversizeActualSpeechLeavesAllFifteenTurnCheckpointsUnprocessed() = runBlocking {
        val app = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
        repeat(20) { i ->
            val raw = event(i, if (i == 0) "过长语音正文".repeat(9_000)
                else "一条普通发言-$i")
            SharedExperienceTimeline.record(raw.id, role, raw.channel, raw.speaker,
                raw.content, raw.occurredAt, triggerExtraction = false,
                sessionId = raw.sessionId, source = raw.source,
                evidenceKind = raw.evidenceKind)
        }
        assertEquals(15, LuluRepositories.memory.pendingMessageCount(role))
        val result = runCatching { LuluRepositories.memory.summarizeNow(role) }
        assertTrue(result.isFailure)
        assertEquals(15, LuluRepositories.memory.pendingMessageCount(role))
        assertTrue(LuluRepositories.memory.snapshot(role).isEmpty())
    }

    @Test(timeout = 60000) fun interruptedSplitBatchResumesRemainingTurnsAfterRepositoryReopen() = runBlocking {
        val server = MockWebServer()
        fun reply(text: String): MockResponse = MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("choices", JSONArray().put(JSONObject()
                .put("message", JSONObject().put("content", text))
                .put("finish_reason", "stop"))).toString())
        server.enqueue(reply("[]")) // First complete subset commits.
        server.enqueue(reply("not-json")) // Second subset fails without checkpoint.
        server.enqueue(reply("[]")) // Restart resumes the second subset only.
        server.start()
        try {
            val app = RuntimeEnvironment.getApplication() as Context
            app.getSharedPreferences("lulu_advanced_settings", Context.MODE_PRIVATE).edit()
                .putString("memory_extract_url", server.url("/").toString())
                .putString("memory_extract_key", "test-only")
                .putString("memory_extract_model", "test-memory").commit()
            LuluRepositories.initialize(app)
            SharedExperienceTimeline.initialize(app)
            LuluAiServices.initialize(app)
            repeat(20) { i ->
                val raw = event(i, if (i < 15)
                    "长语音第$i " + "有来源的叙述".repeat(900) + "句末信息-$i"
                    else "最新五轮只保留原话-$i")
                SharedExperienceTimeline.record(raw.id, role, raw.channel,
                    raw.speaker, raw.content, raw.occurredAt,
                    triggerExtraction = false, sessionId = raw.sessionId,
                    source = raw.source, evidenceKind = raw.evidenceKind)
            }
            val pendingBefore = LuluRepositories.memory.pendingMessageCount(role)
            assertEquals(15, pendingBefore)
            assertTrue(runCatching { LuluRepositories.memory.summarizeNow(role) }.isFailure)
            val remaining = LuluRepositories.memory.pendingMessageCount(role)
            assertTrue("Only first complete subrequest should advance",
                remaining in 1..14)
            val prefs = app.getSharedPreferences("lulu_memory_store", Context.MODE_PRIVATE)
            val disk = JSONObject(prefs.getString("state_v1", "{}")!!)
            assertEquals(remaining,
                disk.getJSONObject("extractionContinuationTurns").getInt(role))
            val reloaded = LocalMemoryRepository().apply { initialize(app) }
            assertEquals(remaining, reloaded.pendingMessageCount(role))
            // A malformed (potentially billable) response must not be sent again
            // immediately just because the app or repository was reopened.
            val premature = runCatching { reloaded.summarizeNow(role) }.exceptionOrNull()
            assertTrue(premature is BackgroundModelBudgetDeferred)
            assertEquals(2, server.requestCount)
            assertEquals(remaining, reloaded.pendingMessageCount(role))

            // Simulate the documented retry window elapsing without disabling
            // the production idempotence gate or skipping the remaining turn.
            val attempts = app.getSharedPreferences(
                "lulu_background_model_requests_v1", Context.MODE_PRIVATE)
            val claimKeys = attempts.all.keys.filter { it.startsWith("evidence-attempt:") }
            assertTrue("The failed paid request must have a durable attempt", claimKeys.isNotEmpty())
            val eligibleAt = System.currentTimeMillis() -
                BackgroundModelRequestBudget.SAME_EVIDENCE_RETRY_MS - 1_000L
            assertTrue(attempts.edit().apply {
                claimKeys.forEach { putLong(it, eligibleAt) }
            }.commit())
            reloaded.summarizeNow(role)
            assertEquals(0, reloaded.pendingMessageCount(role))
            assertEquals(3, server.requestCount)
            val after = JSONObject(prefs.getString("state_v1", "{}")!!)
            assertFalse(after.getJSONObject("extractionContinuationTurns").has(role))
            assertTrue(reloaded.hasUnprocessedEvents(role)) // Protected last five remain.
        } finally { server.shutdown() }
    }

}
