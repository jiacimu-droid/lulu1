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
