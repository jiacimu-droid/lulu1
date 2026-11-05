package com.jiacimu.lulu.ai

import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModelVisibleOutputTest {
    private val privateText = "PRIVATE_ANALYSIS_MUST_NEVER_BECOME_SPEECH"

    @Test fun prohibitedContent400DoesNotResendTheSameStreamingPrompt() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":{"code":"prohibited_content","message":"Input blocked"}}"""))
        server.start()
        try {
            val response = gateway.generate(
                "rejection-test", "source snapshot", "decide once",
                "后台主动感知", "test", contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "key", "test"),
                streamResponse = true,
            )
            assertTrue(response.isFailure)
            assertTrue(isNonRetryableModelFailure(response.exceptionOrNull()!!))
            assertEquals("prohibited_content",
                (response.exceptionOrNull() as ModelHttpException).providerCode)
            assertEquals("Content rejection must make only ONE HTTP request", 1, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
        } finally { server.shutdown() }
    }

    @Test fun explicitUnsupportedStreamOptionMayFallbackOnceToNonStreaming() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":{"message":"stream option is not supported by this relay"}}"""))
        server.enqueue(MockResponse().setResponseCode(200)
            .setBody("""{"choices":[{"message":{"content":"确实收到。"}}]}"""))
        server.start()
        try {
            val response = gateway.generate(
                "stream-compat-test", "", "reply once", "stream protocol test", "test",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "key", "test"),
                streamResponse = true,
            ).getOrThrow()
            assertEquals("确实收到。", response.text)
            assertEquals(2, server.requestCount)
            assertTrue(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
            assertFalse(server.takeRequest().body.readUtf8().contains("\"stream\":true"))
        } finally { server.shutdown() }
    }

    @Test fun terminalSseChoiceCompletesWithoutDoneOrSocketEof() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        // The relay has already emitted the model's terminal choice but never
        // sends [DONE]. Anything after that terminal event must not keep the
        // paid request pending or invalidate its completed response.
        val first = """data: {"choices":[{"index":0,"delta":{"content":"已经回答。"},"finish_reason":null}]}"""
        val terminal = """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}],"usage":{"prompt_tokens":12,"completion_tokens":7}}"""
        val late = """data: {"error":{"message":"unexpected relay event after model completion"}}"""
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("$first\n\n$terminal\n\n$late\n\n"))
        server.start()
        try {
            val partial = mutableListOf<String>()
            val result = gateway.generate(
                "finish-without-done", "", "回应", "后台主动感知", "test",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "key", "test"),
                streamResponse = true,
                onStreamText = { partial += it },
            ).getOrThrow()
            assertEquals("已经回答。", result.text)
            assertEquals("stop", result.finishReason)
            assertEquals(7, result.outputTokens)
            assertTrue(partial.any { it == "已经回答。" })
            assertEquals("Already charged generation must not be replayed", 1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun slowRelayFinalMessageIsNotDiscardedWhenDeltaObjectIsEmpty() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        // A relay may buffer for a while, then emit a final message with an
        // empty delta. The client must read the actual text, not mistake the
        // normal thinking delay or that empty delta for an empty model reply.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(1, java.util.concurrent.TimeUnit.SECONDS)
            .setBody("""data: {"choices":[{"delta":{},"message":{"content":"我确实收到你的安排。"},"finish_reason":"stop"}]}

"""))
        server.start()
        try {
            val response = gateway.generate(
                "buffered-final-message", "", "请回复", "后台主动感知", "test",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "key", "test"),
                streamResponse = true,
            ).getOrThrow()
            assertEquals("我确实收到你的安排。", response.text)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun incompleteRelayStreamIsDiagnosedAsEarlyEndWithoutPaidReplay() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("""data: {"choices":[{"delta":{"reasoning_content":"$privateText"}}]}

"""))
        server.start()
        try {
            val response = gateway.generate(
                "early-close", "", "请回复", "后台主动感知", "test",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "key", "test"),
                streamResponse = true,
            )
            assertTrue(response.isFailure)
            val error = response.exceptionOrNull()?.message.orEmpty()
            assertTrue(error, error.contains("提前结束流式连接"))
            assertTrue(error, error.contains("未自动重新请求"))
            assertFalse(error.contains(privateText))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun responseApiPublicTextDeltaIsDeliveredWithoutExposingReasoning() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("""data: {"type":"response.reasoning_summary_text.delta","delta":"$privateText"}

data: {"type":"response.output_text.delta","delta":"现在去看你的待办。"}

data: {"type":"response.completed","response":{}}

"""))
        server.start()
        try {
            val response = gateway.generate(
                "response-events", "", "请回复", "后台主动感知", "test",
                contextMode = CompanionContextMode.Isolated,
                connectionOverride = ModelConnection(server.url("/").toString(), "key", "test"),
                streamResponse = true,
            ).getOrThrow()
            assertEquals("现在去看你的待办。", response.text)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun jsonReasoningFieldsCannotSubstituteForMissingContent() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        listOf("reasoning_content", "reasoning", "analysis", "thinking").forEach { field ->
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"$field":"$privateText"}}]}"""))
        }
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"我在。","reasoning_content":"$privateText"}}]}"""))
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test", "test")
            repeat(4) {
                val result = gateway.generate("output-test", "", "回应", "输出边界测试", "test",
                    connectionOverride = connection, contextMode = CompanionContextMode.Isolated)
                assertTrue(result.isFailure)
                assertFalse(result.exceptionOrNull()?.message.orEmpty().contains(privateText))
            }
            val actual = gateway.generate("output-test", "", "回应", "输出边界测试", "test",
                connectionOverride = connection, contextMode = CompanionContextMode.Isolated).getOrThrow()
            assertEquals("我在。", actual.text)
        } finally { server.shutdown() }
    }

    @Test fun streamedAndUnexpectedSseNeverPromoteReasoningToSpokenContent() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        val gateway = CompanionModelGateway(ModelConnectionStore.create(app))
        val server = MockWebServer()
        val reasoning = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$privateText\"}}]}\n\n"
        val content = "data: {\"choices\":[{\"delta\":{\"content\":\"听见啦。\"}}]}\n\n"
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(reasoning + "data: [DONE]\n\n"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(reasoning + content + "data: [DONE]\n\n"))
        }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test", "test")
            for (stream in listOf(false, true)) {
                val partial = mutableListOf<String>()
                val failed = gateway.generate("output-test", "", "回应", "输出边界测试", "test",
                    connectionOverride = connection, contextMode = CompanionContextMode.Isolated,
                    streamResponse = stream, onStreamText = { partial += it })
                assertTrue(failed.isFailure)
                assertTrue(partial.isEmpty())
                val spoken = gateway.generate("output-test", "", "回应", "输出边界测试", "test",
                    connectionOverride = connection, contextMode = CompanionContextMode.Isolated,
                    streamResponse = stream, onStreamText = { partial += it }).getOrThrow()
                assertEquals("听见啦。", spoken.text)
                assertFalse(partial.any { it.contains(privateText) })
            }
        } finally { server.shutdown() }
    }

    @Test fun sseParserRejectsPrivateOnlyOutputAndKeepsRealContent() {
        val privateOnly = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"$privateText\"}}]}\n\ndata: [DONE]\n\n"
        assertTrue(runCatching { modelResponseObject(privateOnly) }.isFailure)
        val parsed = modelResponseObject(privateOnly.replace("data: [DONE]",
            "data: {\"choices\":[{\"delta\":{\"content\":\"正文\"}}]}\n\ndata: [DONE]"))
        assertEquals("正文", parsed.getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content"))
        assertFalse(parsed.toString().contains(privateText))
    }

    @Test fun typedPrivateBlocksCannotMasqueradeAsTextInMultimodalOutput() {
        val blocks = org.json.JSONArray()
            .put(JSONObject().put("type", "thinking").put("text", privateText))
            .put(JSONObject().put("type", "reasoning").put("text", privateText))
            .put(JSONObject().put("type", "text").put("text", "这句才说出口。"))
        assertEquals("这句才说出口。", visibleModelText(blocks))
        assertEquals("真正的正文", visibleModelText(JSONObject().put("type", "message")
            .put("content", org.json.JSONArray().put(JSONObject().put("type", "output_text")
                .put("text", "真正的正文")))))
    }
}
