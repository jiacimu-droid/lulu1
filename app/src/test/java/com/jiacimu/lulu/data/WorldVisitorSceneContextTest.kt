package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class WorldVisitorSceneContextTest {
    private val t = Instant.parse("2026-11-17T05:00:00Z")
    private fun event(
        id: String, owner: String = "a", place: String = "cafe",
        seconds: Long = 0, source: String = "digital-world-arrival",
    ) = SharedTimelineEvent(
        id = id, characterId = owner, channel = "数字世界·到访通知",
        speaker = "地图事件", content = if (id.startsWith("world-arrival-leave-"))
            "主人离开了咖啡角；这不表示你当时亲眼看见" else
            "主人来到咖啡角；这只是通知",
        occurredAt = t.plusSeconds(seconds), sessionId = place, source = source,
        evidenceKind = EventEvidenceKind.Observation,
    )

    @Test fun keepsArrivalAndDepartureInActualOrderWithoutAssumingPresence() {
        val arrived = event("world-arrival-enter-a", seconds = -60)
        val left = event("world-arrival-leave-b", seconds = -12)
        val chosen = WorldVisitorSceneContext.select(listOf(left, arrived), "cafe", t)
        assertEquals(listOf(arrived.id, left.id), chosen.map { it.id })
        val text = WorldVisitorSceneContext.describe(chosen)
        assertTrue(text.contains("到访"))
        assertTrue(text.contains("离场"))
        assertTrue(text.contains("不保证现在还在"))
    }

    @Test fun excludesOtherPlacesFutureUnverifiedAndLegacyNotifications() {
        val matched = event("world-arrival-enter-current", seconds = -5)
        val inputs = listOf(
            event("world-arrival-leave-other", place = "park", seconds = -2),
            event("world-arrival-leave-future", seconds = 10),
            event("world-arrival-enter-otherrole", owner = "b", seconds = -4),
            event("world-arrival-enter-other-source", source = "fiction", seconds = -3),
            event("world-arrival-legacy", seconds = -3),
            matched,
        )
        val onlyOwner = WorldVisitorSceneContext.select(inputs.filter { it.characterId == "a" }, "cafe", t)
        assertEquals(listOf(matched.id), onlyOwner.map { it.id })
    }

    @Test fun missingNotificationDoesNotInventOwnerAbsence() {
        val message = WorldVisitorSceneContext.describe(emptyList())
        assertTrue(message.contains("不能断言主人在场或不在场"))
    }
}
