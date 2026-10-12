package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class DigitalWorldReadingRouteTest {
    @Test fun homeShelfIsSelectedWithoutFakeTravel() {
        val plan = DigitalWorldReadingRoute.choose("home:reader", "home:reader", "shelf-1")
        assertNull(plan.travelAction)
        assertEquals("shelf-1", plan.shelfId)
    }

    @Test fun aReaderAwayFromHomeReturnsToTheirRealShelf() {
        val plan = DigitalWorldReadingRoute.choose(DigitalWorldPublicPlaces.CAFE,
            "home:reader", "shelf-1")
        assertEquals("go_home", plan.travelAction)
        assertEquals("shelf-1", plan.shelfId)
    }

    @Test fun aReaderAlreadyAtTheLibraryDoesNotTeleportHome() {
        val plan = DigitalWorldReadingRoute.choose(DigitalWorldPublicPlaces.READING_LOUNGE,
            "home:reader", "shelf-1")
        assertNull(plan.travelAction)
        assertNull(plan.shelfId)
    }

    @Test fun deletingTheOnlyShelfKeepsItDeletedAndUsesPublicLibrary() {
        val plan = DigitalWorldReadingRoute.choose("home:reader", "home:reader", null)
        assertEquals("visit_public_place", plan.travelAction)
        assertNull(plan.shelfId)
    }
}
