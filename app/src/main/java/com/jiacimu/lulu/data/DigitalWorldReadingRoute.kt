package com.jiacimu.lulu.data

/** Route to a real reading place. Does not create imaginary books or furniture. */
internal data class DigitalWorldReadingPlan(val travelAction: String?, val shelfId: String?)

internal object DigitalWorldReadingRoute {
    fun choose(currentLocation: String, homeLocation: String, homeShelfId: String?): DigitalWorldReadingPlan {
        // A person's own bookshelf is the only physical reading station.
        // Never resurrect a shelf deliberately removed by the user.
        return DigitalWorldReadingPlan(
            if (homeShelfId != null && currentLocation != homeLocation) "go_home" else null,
            homeShelfId,
        )
    }
}
