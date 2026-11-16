package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import java.time.Instant
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
class RecentSemanticTimelineIntegrationTest {
    @Before fun before() { MemoryTestEnvironment.release() }
    @After fun after() { MemoryTestEnvironment.release() }

    @Test fun persistedLongRelicRunDoesNotEvictEarlierRealConversation() {
        val app = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(app)
        MigratedDomainStores.initialize(app)
        SharedExperienceTimeline.initialize(app)
        val role = "semantic-window-integration-role"
        val at = Instant.parse("2026-11-17T02:00:00Z")
        SharedExperienceTimeline.record(
            "first-message", role, "私聊", "主人", "刚才我们说好的事情",
            at, triggerExtraction = false, sessionId = "real-chat",
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        repeat(120) { index ->
            SharedExperienceTimeline.record(
                "relic:long-run:$role:$index", role, "共同游戏《迷雾摸金》", "游戏实况",
                "真实动作 $index", at.plusSeconds(index + 1L),
                triggerExtraction = false, source = "game-relic",
                evidenceKind = EventEvidenceKind.ToolResult)
        }
        SharedExperienceTimeline.record(
            "last-message", role, "私聊", "江渡", "记得刚才说过的事情",
            at.plusSeconds(300), triggerExtraction = false, sessionId = "real-chat",
            source = "message", evidenceKind = EventEvidenceKind.CharacterStatement)
        val repository = LocalMemoryRepository().apply { initialize(app) }
        val turns = repository.contextTimelineTurns(role)
        assertEquals(3, turns.size)
        assertEquals(120, turns.single { it.kind == RecentSemanticTimeline.Kind.EXPERIENCE }.events.size)
        assertTrue(turns.flatMap { it.events }.any { it.id == "first-message" })
        assertTrue(turns.flatMap { it.events }.any { it.id == "last-message" })
        assertTrue(repository.contextTimelineEvents(role).all { it.characterId == role })
    }
}
