package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class DigitalWorldArrivalAudienceTest {
    @Test fun visitingAHomeNotifiesOnlyItsOwner() {
        assertEquals(setOf("owner"), DigitalWorldArrivalAudience.recipients(
            "home:owner", setOf("friend", "owner"), "owner", setOf("guest")))
    }
    @Test fun publicPlaceNotifiesLinkedRolesAndWitnessesOnly() {
        assertEquals(setOf("friend", "onsite"), DigitalWorldArrivalAudience.recipients(
            "shared:reading_lounge", setOf("friend"), null, setOf("onsite")))
    }
    @Test fun privateOrPreviewLocationsDoNotBroadcast() {
        assertTrue(DigitalWorldArrivalAudience.recipients("preview:3d-room",
            setOf("friend"), null, emptySet()).isEmpty())
    }
}
