package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MeetingLocationRenderingTest {
    @Test fun publicMeetingScenesKeepTheirActualVenueInsteadOfRenderingTheEntrance() {
        val venues = mapOf(
            "游戏馆" to DigitalWorldPublicPlaces.GAME_HALL,
            "阅读馆" to DigitalWorldPublicPlaces.READING_LOUNGE,
            "浮光咖啡角" to DigitalWorldPublicPlaces.CAFE,
            "共生庭院" to DigitalWorldPublicPlaces.COURTYARD,
        )
        venues.forEach { (label, code) ->
            assertEquals(code, DigitalWorldStore.meetingLocationCode(label, DigitalWorldState()))
        }
        val home = DigitalHome("owner", "江渡的家", Instant.EPOCH)
        val world = DigitalWorldState(homes = mapOf(home.characterId to home))
        assertEquals("home:owner", DigitalWorldStore.meetingLocationCode(home.name, world))
        assertEquals(DigitalWorldStore.CLOUD_MEADOW, DigitalWorldStore.meetingLocationCode("云眠原", world))
        assertEquals(DigitalWorldStore.ARRIVAL, DigitalWorldStore.meetingLocationCode("已删除的地点", world))
    }
}
