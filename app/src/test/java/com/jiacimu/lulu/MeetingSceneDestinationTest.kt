package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class MeetingSceneDestinationTest {
    private val roomFurniture = setOf("bed-01", "sofa-02")

    @Test fun onlyPhysicalItemIdsAlreadyPresentInThisRoomCanBeSelected() {
        assertEquals("bed-01", meetingValidatedDestinationItemId(
            "bed-01", "", true, roomFurniture))
        assertEquals("", meetingValidatedDestinationItemId(
            "a bed by the window", "", true, roomFurniture))
        assertEquals("", meetingValidatedDestinationItemId(
            "furniture-from-another-home", "", true, roomFurniture))
    }

    @Test fun omissionPreservesExistingDestinationWhileExplicitEmptyCancels() {
        assertEquals("sofa-02", meetingValidatedDestinationItemId(
            "", "sofa-02", false, roomFurniture))
        assertEquals("", meetingValidatedDestinationItemId(
            "", "sofa-02", true, roomFurniture))
        assertEquals("", meetingValidatedDestinationItemId(
            "", "item-deleted-from-room", false, roomFurniture))
    }
}
