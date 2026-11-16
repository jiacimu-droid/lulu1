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

    @Test fun furnitureActionMustBeOfferedByExactTargetItem() {
        val bedOffers = setOf("sit_on_bed", "make_bed", "inspect_item")
        assertEquals("make_bed", meetingValidatedDestinationActivityId("make_bed", bedOffers))
        assertEquals("", meetingValidatedDestinationActivityId("toggle_lamp", bedOffers))
        assertEquals("", meetingValidatedDestinationActivityId("invented_activity", bedOffers))
        assertEquals("", meetingValidatedDestinationActivityId("", bedOffers))
        assertEquals("", meetingValidatedDestinationActivityId("sit_on_bed", emptySet()))
    }

    @Test fun streetGuidanceOnlyAcceptsRealAddressesAndCanBeCancelled() {
        val available = setOf("shared:cafe", "home:role-1")
        assertEquals("shared:cafe", meetingValidatedStreetCode(
            "shared:cafe", "", true, available))
        assertEquals("", meetingValidatedStreetCode(
            "made-up-castle", "", true, available))
        assertEquals("home:role-1", meetingValidatedStreetCode(
            "", "home:role-1", false, available))
        assertEquals("", meetingValidatedStreetCode(
            "", "shared:cafe", true, available))
        assertEquals("", meetingValidatedStreetCode(
            "", "removed-address", false, available))
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
