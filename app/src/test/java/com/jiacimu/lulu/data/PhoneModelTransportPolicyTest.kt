package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.system.LuluDeviceToolBridge
import com.jiacimu.lulu.system.PhoneModelTransportPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test(timeout = 60_000) fun explicitStreamRejectionRetriesTheSamePhoneDecisionWithoutStreaming() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val actor = MigratedDomainStores.characters.create("来电主体", "慢慢想清楚再说")
        val agreement = "只有我明确同意才再联系；沉默不等于许可。"
        assertTrue(CharacterSocialRelationship.setAgreement(actor.characterId, "user", "原约定", agreement))
        val transcript = "如果我没明确说可以，今晚就不要再拨电话。"
        val decisionSpeech = "好，我听见了。"
        val decision = JSONObject().put("action", "reply").put("text", decisionSpeech)
        val response = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", decision.toString())).put("finish_reason", "stop")))
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(400)
            .setBody("""{"error":{"message":"Stream mode is unsupported by this endpoint."}}"""))
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString()))
        server.start()
        try {
            val config = LuluAiServices.connectionStore.saveConfiguration(
                null, "phone-retry", server.url("/").toString(), "test-only",
            )
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test-model")
            val result = LuluDeviceToolBridge.respond(
                actor.characterId, "", transcript, "电话协议回退",
                archiveId = archive.id, sceneContext = "正在与用户进行一对一实时电话",
                phoneCallId = "phone-stream-retry-${actor.characterId}",
            ).getOrThrow()
            assertEquals(decisionSpeech, result.text)
            assertEquals(2, server.requestCount)
            val first = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            val retry = JSONObject(checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8())
            assertTrue(first.getBoolean("stream"))
            assertFalse(retry.optBoolean("stream"))
            assertFalse(retry.has("stream_options"))
            first.remove("stream")
            first.remove("stream_options")
            assertEquals(first.toString(), retry.toString())
            val messages = retry.getJSONArray("messages")
            val system = messages.getJSONObject(0).getString("content")
            assertTrue(system.contains(actor.displayName))
            assertTrue(system.contains(agreement))
            assertTrue((1 until messages.length()).any {
                messages.getJSONObject(it).getString("content").contains(transcript)
            })
        } finally { server.shutdown() }
    }

    @Test(timeout = 60_000) fun interruptedPhoneRequestDoesNotCommitAndNextUtteranceCanRespond() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        LuluDeviceToolBridge.initialize(context)
        val actor = MigratedDomainStores.characters.create("被打断的电话角色", "先听清再回答")
        val role = actor.characterId
        val original = "如果下雨，今晚就取消出门。"
        val correction = "等等，说反了。只取消明天的安排，今晚照旧。"
        val speech = "听清了，今晚照旧。"
        val response = JSONObject().put("choices", JSONArray().put(JSONObject()
            .put("message", JSONObject().put("content", JSONObject().put("action", "reply")
                .put("text", speech).toString())).put("finish_reason", "stop")))
        MockWebServer().use { server ->
            val releaseOldResponse = CountDownLatch(1)
            val firstRequest = AtomicBoolean(true)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    if (firstRequest.getAndSet(false)) releaseOldResponse.await(10, TimeUnit.SECONDS)
                    return MockResponse().setHeader("Content-Type", "application/json").setBody(response.toString())
                }
            }
            server.start(java.net.InetAddress.getByName("127.0.0.1"), 0)
            val endpoint = server.url("/").newBuilder().host("127.0.0.1").build().toString()
            val config = LuluAiServices.connectionStore.saveConfiguration(
                null, "phone-interruption", endpoint, "test-only",
            )
            val archive = LuluAiServices.connectionStore.addArchive(config.id, "test-model")
            var published = false
            var hangup = false
            suspend fun respond(transcript: String) = LuluDeviceToolBridge.respond(
                role, "", transcript, "电话插话",
                archiveId = archive.id, sceneContext = "正在与用户进行一对一实时电话",
                phoneCallId = "interrupted-$role", onCharacterHangup = { hangup = true },
            )
            val before = CharacterInnerLifeStore.snapshot(role).toString()
            fun authoredEffects() = SharedExperienceTimeline.all(role).filter {
                it.evidenceKind == EventEvidenceKind.CharacterStatement ||
                    it.evidenceKind == EventEvidenceKind.ToolResult
            }.map { it.id }
            val priorEffects = authoredEffects()
            val pending = launch {
                respond(original)
                published = true
            }
            try {
                val first = withContext(Dispatchers.IO) {
                    checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
                }
                assertTrue(first.contains(original))
                assertTrue(JSONObject(first).getBoolean("stream"))
                withTimeout(5_000L) { pending.cancelAndJoin() }
                releaseOldResponse.countDown()
                assertFalse(published)
                assertFalse(hangup)
                assertEquals(1, server.requestCount)
                assertEquals(before, CharacterInnerLifeStore.snapshot(role).toString())
                assertEquals(priorEffects, authoredEffects())

                assertEquals(speech, respond(correction).getOrThrow().text)
                val followUp = checkNotNull(server.takeRequest(5, TimeUnit.SECONDS)).body.readUtf8()
                assertTrue(followUp.contains(correction))
                assertTrue(followUp.contains(actor.displayName))
                assertEquals(2, server.requestCount)
            } finally {
                pending.cancel()
                releaseOldResponse.countDown()
            }
        }
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
