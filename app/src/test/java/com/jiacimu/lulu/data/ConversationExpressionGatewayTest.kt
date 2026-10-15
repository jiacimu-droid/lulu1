package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
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
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConversationExpressionGatewayTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun renderAndRepairKeepTheSameVoiceWithoutReceivingASecondPlannerContract() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = MigratedDomainStores.characters.create("表达通道回归角色", "说话直接，有冷幽默")
        CharacterLifeStore.setProfile(role.characterId, "speechHabits", "熟悉时说话干脆，会接冷笑话")
        val sources = listOf("聊天表达渲染", "聊天表达自然度修复", "工具结果·聊天自然表达修复", "普通角色决定")
        val server = MockWebServer()
        repeat(sources.size) { server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"我在呢。"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            sources.forEach { source ->
                LuluAiServices.gateway.generate(role.characterId, "已决定接住这一句招呼", "输出本人的自然表达",
                    source, "test", connectionOverride = connection,
                    memoryRequest = UnifiedMemoryRequest(currentInput = "小宝宝", sceneContext = "私聊"))
                    .getOrThrow()
                val request = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val system = request.getJSONArray("messages").getJSONObject(0).getString("content")
                if (CharacterExpressionGuide.isExpressionOnly(source)) {
                    assertTrue(system.contains("【同一人物的表达层】"))
                    assertTrue(system.contains("熟悉时说话干脆，会接冷笑话"))
                    assertFalse(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【人物的基本原则"))
                } else {
                    assertTrue(system.contains("【统一角色决策协议】"))
                    assertFalse(system.contains("【同一人物的表达层】"))
                }
            }
            assertEquals(sources.size, server.requestCount)
        } finally { server.shutdown() }
    }
}
