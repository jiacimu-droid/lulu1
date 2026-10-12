package com.jiacimu.lulu.data

/** Route to a real reading place. Does not create imaginary books or furniture. */
internal data class DigitalWorldReadingPlan(val travelAction: String?, val shelfId: String?)

internal object DigitalWorldReadingRoute {
    fun choose(currentLocation: String, homeLocation: String, homeShelfId: String?): DigitalWorldReadingPlan {
        // Visiting a real library does not require returning to one's own home.
        if (currentLocation == DigitalWorldPublicPlaces.READING_LOUNGE)
            return DigitalWorldReadingPlan(null, null)
        if (!homeShelfId.isNullOrBlank())
            return DigitalWorldReadingPlan(if (currentLocation == homeLocation) null else "go_home", homeShelfId)
        // A removed bookshelf must remain removed; the library is the fallback.
        return DigitalWorldReadingPlan("visit_public_place", null)
    }
}
