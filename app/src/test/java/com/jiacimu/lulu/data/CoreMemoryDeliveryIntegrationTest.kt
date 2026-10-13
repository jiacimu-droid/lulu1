package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import com.jiacimu.lulu.ai.CompanionContextMode
import com.jiacimu.lulu.ai.LuluAiServices
import com.jiacimu.lulu.ai.ModelConnection
import com.jiacimu.lulu.core.MemoryEntry
import com.jiacimu.lulu.core.MemoryKind
import com.jiacimu.lulu.core.MemoryTier
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
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
class CoreMemoryDeliveryIntegrationTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun everyRealWorldModelModeReceivesCoreWithoutOrdinaryRecallAndIsolatedModesDoNot() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        LuluAiServices.initialize(context)
        val role = "core-gateway-integration"
        LuluRepositories.memory.save(MemoryEntry(
            id = "core-one", characterId = role, content = "核心事实：我喜欢称呼宝宝",
            kind = MemoryKind.Fact, source = "手动", occurredAt = null,
            createdAt = Instant.now(), strength = 5, pinned = true,
            canRecallProactively = true, tier = MemoryTier.Core,
        ))
        val server = MockWebServer()
        repeat(4) { server.enqueue(MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody("""{"choices":[{"message":{"content":"知道了"},"finish_reason":"stop"}]}""")) }
        server.start()
        try {
            val connection = ModelConnection(server.url("/").toString(), "test-only", "test")
            val cases = listOf(
                Triple(CompanionContextMode.Full, "角色普通私聊", true),
                Triple(CompanionContextMode.CharacterAndScenario, "聊天表达渲染", true),
                Triple(CompanionContextMode.PersonaAndScenario, "跨世界剧情", false),
                Triple(CompanionContextMode.Isolated, "独立故事", false),
            )
            for ((mode, source, expectsCore) in cases) {
                LuluAiServices.gateway.generate(role, "用户正在说话", "自然回应", source, "test",
                    connectionOverride = connection, contextMode = mode).getOrThrow()
                val body = JSONObject(server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8())
                val system = body.getJSONArray("messages").getJSONObject(0).getString("content")
                assertEquals(source, expectsCore, system.contains("核心事实：我喜欢称呼宝宝"))
                if (expectsCore) assertTrue(system.contains("持续生效的核心记忆"))
            }
            val audit = CoreMemoryInjectionAudit.recent(role)
            assertEquals(2, audit.size)
            assertTrue(audit.all { it.coreIds == listOf("core-one") })
            assertEquals("聊天表达渲染", audit.first().source)
            assertEquals("角色普通私聊", audit.last().source)
            assertEquals(4, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun deletingCoreMemoryAlsoRedactsItsInjectionHistory() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(context)
        val role = "core-redaction"
        LuluRepositories.memory.save(MemoryEntry(
            id = "will-delete", characterId = role, content = "已经撤回的个人事实",
            kind = MemoryKind.Fact, source = "手动", occurredAt = null,
            createdAt = Instant.now(), strength = 5, pinned = true,
            canRecallProactively = true, tier = MemoryTier.Core,
        ))
        CoreMemoryInjectionAudit.recordSuccess(role, "普通对话", "Full", listOf("will-delete"))
        assertTrue(CoreMemoryInjectionAudit.recent(role).first().coreIds.contains("will-delete"))
        LuluRepositories.memory.delete("will-delete")
        assertFalse(CoreMemoryInjectionAudit.recent(role).first().coreIds.contains("will-delete"))
        assertTrue(CoreMemoryDelivery.active(role).isEmpty())
        CoreMemoryInjectionAudit.clearCharacter(role)
        assertTrue(CoreMemoryInjectionAudit.recent(role).isEmpty())
    }

    @Test fun coreAuditSurvivesReinitializationAndPreservesNoPrivateText() {
        val context = RuntimeEnvironment.getApplication() as Context
        CoreMemoryInjectionAudit.initialize(context)
        val role = "core-audit-persist"
        CoreMemoryInjectionAudit.recordSuccess(role, "角色问候", "Full",
            listOf("memory-1", "memory-2"), Instant.parse("2026-10-13T11:00:00Z"))
        CoreMemoryInjectionAudit.initialize(context)
        assertEquals(listOf("memory-1", "memory-2"),
            CoreMemoryInjectionAudit.recent(role).first().coreIds)
        assertEquals("Full", CoreMemoryInjectionAudit.recent(role).first().contextMode)
        assertFalse(context.getSharedPreferences("lulu_core_memory_injection_audit_v1",
            Context.MODE_PRIVATE).getString("role:$role", "").orEmpty().contains("核心事实正文"))
    }
}
