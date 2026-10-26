package com.jiacimu.lulu.ai

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.data.*
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class JevDirectionsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    @Before fun before() {
        MemoryTestEnvironment.release()
        ScopedModelSelections::class.java.getDeclaredField("prefs").apply { isAccessible = true }
            .set(ScopedModelSelections, null)
        context.getSharedPreferences("lulu_model_connection", 0).edit().clear().commit()
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        ScopedModelSelections.clear(ScopedModelSelections.JEV)
        ScopedModelSelections.clear(ScopedModelSelections.EXPRESSION)
        JevDirections.setLongContext(context, false)
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure)
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
    }
    @After fun after() {
        ScopedModelSelections.clear(ScopedModelSelections.JEV)
        ScopedModelSelections.clear(ScopedModelSelections.EXPRESSION)
        MemoryTestEnvironment.release()
    }
    private fun args(count: Int = 2) = JSONObject().put("state", "本人已知的事情；猜测不是事实")
        .put("questions", JSONObject().apply { repeat(count) { index ->
            put("direction-$index", JSONObject().put("instructions", "我想分别考虑的方向-$index"))
        } })
    private fun response(count: Int = 2) = JSONObject().put("model", "jev-1.14.0")
        .put("answers", JSONObject().apply { repeat(count) { index ->
            put("direction-$index", JSONObject().put("type", "noul").put("noul", if (index % 2 == 0) 0.01 else 0.99))
        } }).put("usage", JSONObject().put("input_tokens", 600).put("output_tokens", 40))
    private fun configured(server: MockWebServer): ModelArchive {
        val store = LuluAiServices.connectionStore
        val config = store.saveConfiguration(null, "Jev fixture", server.url("/v1").toString(), "fixture-key")
        return store.addArchive(config.id, "jev-1.14.0").also { ScopedModelSelections.select(ScopedModelSelections.JEV, it.id) }
    }

    @Test fun optionalRolesNeverInheritActiveChatArchive() {
        val store = LuluAiServices.connectionStore
        val config = store.saveConfiguration(null, "chat", "https://example.test/v1", "test")
        store.addArchive(config.id, "chat-model")
        assertNull(ScopedModelSelections.selectedArchiveId(ScopedModelSelections.JEV))
        assertNull(ScopedModelSelections.selectedArchiveId(ScopedModelSelections.EXPRESSION))
        assertFalse(JevDirections.configured())
        assertEquals("", JevDirections.context())
        assertFalse(CapabilityRegistry.allows(context, "role", CapabilityRegistry.find(JevDirections.TOOL)!!, true))
    }

    @Test fun savingOrReselectingNativeJevCannotTakeOverLegacyChatFallback() {
        val store = LuluAiServices.connectionStore
        val chatConfig = store.saveConfiguration(null, "chat", "https://chat.test/v1", "test")
        val chat = store.addArchive(chatConfig.id, "chat-model")
        val nativeConfig = store.saveConfiguration(null, "native", "https://api.typesafe.ai/v1", "test")
        val native = store.addArchive(nativeConfig.id, "jev-1.14.0")
        assertEquals(chat.id, store.library.value.activeArchiveId)
        store.addArchive(nativeConfig.id, "jev-1.14.0")
        assertEquals(chat.id, store.library.value.activeArchiveId)
        store.selectArchive(native.id) // Legacy/global archive default may still be stored.
        for (usage in ModelUsage.entries) assertEquals(chat.id, store.selectedArchiveId(usage))
        store.removeArchive(chat.id)
        for (usage in ModelUsage.entries) assertNull(store.selectedArchiveId(usage))
    }

    @Test fun allQuestionsAndBothLowAndHighProbabilitiesRemainAvailableWithoutSelectingActions() {
        val submitted = args(20).put("state", "原始材料".repeat(20_000))
        val request = JevDirections.request("jev-1.14.0", submitted)
        assertEquals(submitted.getString("state"), request.getString("state"))
        assertEquals(20, request.getJSONObject("questions").length())
        val result = JevDirections.result(request, response(20))
        val directions = result.getJSONArray("directions")
        val probabilities = (0 until directions.length()).map { directions.getJSONObject(it).getDouble("noul") }
        assertEquals(20, directions.length())
        assertTrue(probabilities.contains(0.01))
        assertTrue(probabilities.contains(0.99))
        assertFalse(result.has("action"))
        assertFalse(result.has("selectedDirection"))
        assertTrue(result.getString("interpretation").contains("不是新发生的事实"))
    }

    @Test fun incompleteAndInvalidResponsesCannotBeSubmittedAsSuccessfulReference() {
        val request = JevDirections.request("jev-1.14.0", args())
        val missing = response().apply { getJSONObject("answers").remove("direction-1") }
        assertTrue(runCatching { JevDirections.result(request, missing) }.isFailure)
        for (value in listOf<Any>(-0.1, 1.1, "0.8")) {
            val invalid = response().apply { getJSONObject("answers").getJSONObject("direction-0").put("noul", value) }
            assertTrue(runCatching { JevDirections.result(request, invalid) }.isFailure)
        }
        assertTrue(runCatching { JevDirections.request("chat-model", args()) }.isFailure)
        assertTrue(runCatching { JevDirections.request("jev-1.14.0", args().put("questions", JSONObject())) }.isFailure)
    }

    @Test fun nativeRequestBatchesDirectionsAndLongContextIsExplicit() {
        val server = MockWebServer()
        repeat(2) { server.enqueue(MockResponse().setBody(response().toString())) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/v1").toString(), "fixture-key", "jev-1.14.0")
            JevDirections.exchange(connection, args(), false)
            val normal = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/systemone", normal.path)
            assertEquals("Bearer fixture-key", normal.getHeader("Authorization"))
            assertNull(normal.getHeader("TypeSafe-Danger-Zone-Long-Context"))
            assertEquals(2, JSONObject(normal.body.readUtf8()).getJSONObject("questions").length())
            JevDirections.exchange(connection, args(), true)
            assertEquals("true", server.takeRequest(5, TimeUnit.SECONDS)!!.getHeader("TypeSafe-Danger-Zone-Long-Context"))
            assertEquals(2, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun serviceFailureDoesNotRetryOrCallChatInstead() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(429))
        server.start()
        try {
            val result = runCatching { JevDirections.exchange(
                ModelConnection(server.url("/v1").toString(), "fixture-key", "jev-1.14.0"), args(), false) }
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()!!.message!!.contains("HTTP 429"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun disabledReferenceDoesNotCallAnyProvider() = runBlocking {
        val result = JSONObject(JevDirections.evaluate(context, "role", args()))
        assertFalse(result.optBoolean("success"))
        assertEquals("waiting_user", result.optString("status"))
    }

    @Test fun realToolRouterPersistsOneReferenceAndReplayDoesNotPayAgain() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody(response().toString()))
        server.start()
        try {
            configured(server)
            val role = MigratedDomainStores.characters.create("思路参考测试人", "保留自己的判断")
            val first = ToolRouter.execute(context, role.characterId, JevDirections.TOOL, args(), requestId = "once")
            val replay = ToolRouter.execute(context, role.characterId, JevDirections.TOOL, args(), requestId = "once")
            assertEquals(first, replay)
            assertTrue(JSONObject(first).getBoolean("success"))
            assertEquals(1, server.requestCount)
            val evidence = SharedExperienceTimeline.eventsByIds(role.characterId,
                listOf("tool-${role.characterId}-once")).single()
            assertEquals(EventEvidenceKind.ToolResult, evidence.evidenceKind)
            assertTrue(SearchReceiptFeedback.completedEvidence(evidence))
            assertTrue(CharacterPerceptionContext.stimulus(evidence).description.contains("direction-1"))
            assertTrue(SharedExperienceTimeline.eventsByIds("other-role", listOf(evidence.id)).isEmpty())
            assertFalse(CapabilityRegistry.allows(context, "resident:any", CapabilityRegistry.find(JevDirections.TOOL)!!, false))
        } finally { server.shutdown() }
    }

    @Test fun everyReturnedDirectionReentersPerceptionInsteadOfBeingCutByRequestFingerprint() {
        val result = JevDirections.result(JevDirections.request("jev-1.14.0", args(60)), response(60))
        val execution = JSONObject().put("tool", JevDirections.TOOL).put("status", "succeeded")
            .put("fingerprint", "large-original-state".repeat(400)).put("result", result.toString())
        val event = SharedTimelineEvent("jev-reference", "role", "实际工具执行", "执行器", execution.toString(),
            Instant.now(), source = "tool-router", evidenceKind = EventEvidenceKind.ToolResult)
        val content = CharacterPerceptionContext.stimulus(event).description
        for (index in 0 until 60) assertTrue(content.contains("direction-$index"))
        assertFalse(content.contains("large-original-state"))
        assertTrue(SearchReceiptFeedback.from(JevDirections.TOOL, args(60), result)!!.details.contains("direction-59"))
    }
}
