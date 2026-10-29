package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.system.PhoneModelTransportPolicy
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneModelTransportPolicyTest {
    @Before fun before() {
        MemoryTestEnvironment.release()
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
    }

    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun receivingPhoneSseDoesNotRequireUnvalidatedTextPlayback() {
        assertTrue(PhoneModelTransportPolicy.shouldStream(
            "正在与用户进行一对一实时电话", false, false, false))
        assertTrue(PhoneModelTransportPolicy.shouldStream(
            "电话中继续哄睡", false, false, false))
        assertFalse(PhoneModelTransportPolicy.shouldStream(
            "正在与用户进行一对一实时电话", true, false, false))
        assertFalse(PhoneModelTransportPolicy.shouldStream(
            "正在与用户进行一对一实时电话", false, true, false))
        assertFalse(PhoneModelTransportPolicy.shouldStream(
            "普通私聊", false, false, false))
        assertTrue(PhoneModelTransportPolicy.shouldStream(
            "普通私聊", false, false, true))
    }

    private fun delta(text: String): String =
        JSONObject().put("choices", JSONArray().put(
            JSONObject().put("delta", JSONObject().put("content", text))
        )).toString()

    @Test fun phoneSseBuffersCompleteDecisionWithoutPartialCallback() = runBlocking {
        val server = MockWebServer()
        val fullReply = """{"action":"reply","text":"你好，听见啦。"}"""
        val part1 = fullReply.take(18)
        val part2 = fullReply.drop(18)
        val responseBody = "data: ${delta(part1)}\n\n" +
            "data: ${delta(part2)}\n\n" +
            "data: [DONE]\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(responseBody))
        server.start()
        try {
            val role = MigratedDomainStores.characters.create("电话流式测试", "温和而独立")
            val result = LuluAiServices.gateway.generate(
                characterId = role.characterId,
                facts = "正在电话里交谈", instruction = "只返回回复JSON",
                source = "电话回复", title = "电话流式完整决策",
                connectionOverride = ModelConnection(server.url("/v1").toString().trimEnd('/'),
                    "test-only-key", "test-model"),
                contextMode = CompanionContextMode.Isolated,
                streamResponse = PhoneModelTransportPolicy.shouldStream("实时电话", false, false, false),
                onStreamText = null,
            ).getOrThrow()
            assertEquals(part1 + part2, result.text)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertEquals("/v1/chat/completions", request.path)
            assertTrue(JSONObject(request.body.readUtf8()).getBoolean("stream"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun providerInvalidArgumentIsNeverReplayedAsAnotherPaidRequest() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400).setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"code":"invalid_argument","message":"Request contains an invalid argument."}}"""))
        server.start()
        try {
            val role = MigratedDomainStores.characters.create("模型错误测试", "保持原意")
            val result = LuluAiServices.gateway.generate(
                characterId = role.characterId, facts = "一次请求", instruction = "正常回复",
                source = "电话回复", title = "不重复扣费",
                connectionOverride = ModelConnection(server.url("/v1").toString().trimEnd('/'),
                    "test-only-key", "test-model"),
                contextMode = CompanionContextMode.Isolated,
                streamResponse = true,
                onStreamText = null,
            )
            assertTrue(result.isFailure)
            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertTrue(JSONObject(request.body.readUtf8()).getBoolean("stream"))
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }
}
