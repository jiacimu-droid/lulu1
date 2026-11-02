package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DigitalWorldBulletinTest {
    @Test fun onlyConfirmedResidentAndPlaceAppearAsWorldNews() {
        val earlier = Instant.parse("2026-10-10T08:00:00Z")
        val later = Instant.parse("2026-10-11T08:00:00Z")
        val resident = DigitalWorldResident(
            id = "r1", name = "阿澈", identity = "在阅读馆整理藏书的常住居民",
            homeLocationCode = DigitalWorldStore.CLOUD_MEADOW, createdAt = later,
        )
        val place = DigitalWorldDiscoveredPlace(
            code = "shared:discovered:library",
            label = "青檐书廊", subtitle = "书廊",
            purpose = "书籍收藏与交流", connectedTo = listOf(DigitalWorldStore.CLOUD_MEADOW),
            createdByCharacterId = "role", createdAt = earlier,
        )
        val notices = DigitalWorldBulletin.collect(DigitalWorldState(), listOf(resident), listOf(place))
        assertEquals(listOf("resident:r1", "place:shared:discovered:library"), notices.map { it.id })
        assertTrue(notices[0].detail.contains("在阅读馆整理藏书"))
        assertTrue(notices[1].detail.contains("书籍收藏"))
    }

    @Test fun onlyAcceptedFriendRequestProducesDatedWorldReceipt() {
        val acceptedAt = Instant.parse("2026-11-02T06:00:00Z")
        val accepted = CharacterFriendRequest(
            id = "confirmed-a", fromId = "a", toId = "b", greeting = "",
            status = "accepted", createdAt = acceptedAt.minusSeconds(600),
            resolvedAt = acceptedAt,
        )
        val pending = CharacterFriendRequest(
            id = "pending-b", fromId = "b", toId = "c", greeting = "",
            status = "pending", createdAt = acceptedAt,
        )
        val receipts = DigitalWorldBulletin.collect(
            DigitalWorldState(), emptyList(), emptyList(), listOf(pending, accepted),
        )
        assertEquals(1, receipts.size)
        assertEquals("friend-accepted:confirmed-a", receipts.single().id)
        assertEquals(acceptedAt, receipts.single().occurredAt)
        assertEquals("社交", receipts.single().category)
        assertTrue(receipts.single().detail.contains("同意"))
        assertFalse(receipts.any { it.id.contains("pending-b") })
    }

    @Test fun ambientAndOrdinaryMovementDoNotFloodTheAnnouncementBoard() {
        val at = Instant.parse("2026-10-11T08:00:00Z")
        val world = DigitalWorldState(events = listOf(
            DigitalWorldEvent("walk", "r", "go_home", "只是回家", at),
            DigitalWorldEvent("furniture", "r", "build_home_item", "新建了一把椅子", at),
            DigitalWorldEvent("home", "r", "home_created", "建造了一处真正的家", at.minusSeconds(60)),
        ))
        val notices = DigitalWorldBulletin.collect(world, emptyList(), emptyList())
        assertEquals(2, notices.size)
        assertFalse(notices.any { it.detail.contains("只是回家") })
        assertTrue(notices.any { it.detail.contains("椅子") })
    }
}
