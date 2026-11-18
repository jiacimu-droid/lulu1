package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

/** Real world choices are offered to the model, never acted out by a prompt. */
class ResidentWorldLifeOpportunitiesTest {
    @Test fun residentHomeHasItsOwnRealAffordancesAndOwner() {
        assertEquals("resident:barista-123",
            DigitalWorldLifeEventStore.homeOwnerForLocation("resident-home:barista-123"))
        assertEquals("role-123",
            DigitalWorldLifeEventStore.homeOwnerForLocation("home:role-123"))
        assertNull(DigitalWorldLifeEventStore.homeOwnerForLocation(DigitalWorldPublicPlaces.CAFE))
        assertNull(DigitalWorldLifeEventStore.homeOwnerForLocation("resident-home:"))
        assertEquals(
            DigitalWorldActivityCatalog.locationOptions("home:role-123"),
            DigitalWorldActivityCatalog.locationOptions("resident-home:barista-123"))
        assertTrue(DigitalWorldActivityCatalog.locationOptions("resident-home:barista-123")
            .any { it.first == "home_pace" })
        assertNotNull(DigitalWorldActivityCatalog.locationActivitySummary(
            "阿岑", "resident-home:barista-123", "home_quiet_rest", "阿岑的家"))
    }

    @Test fun worldChoicesUseLiveActivityIdsAndSeparateSceneryFromPersonalExperience() {
        val prompt = ResidentWorldLifeOpportunities.render(
            locationCode = DigitalWorldPublicPlaces.COURTYARD,
            locationActivities = listOf("courtyard_walk" to "沿庭院散步",
                "garden_pause" to "在植物旁停留"),
            itemActivities = "",
            sceneFacts = "尚未亲眼留意：incidentId=world-101；庭院刚出现一片真实光影",
            boredom = 88,
            sharingUrge = 65,
            verifiedShareContext = "experienceId=own-verified-memory；自己实际读过一段文字",
        )
        assertTrue(prompt.contains("单调感=88/100"))
        assertTrue(prompt.contains("分享冲动=65/100"))
        assertTrue(prompt.contains("activityId=courtyard_walk"))
        assertTrue(prompt.contains("activityId=garden_pause"))
        assertTrue(prompt.contains("worldAction:observe_scene"))
        assertTrue(prompt.contains("incidentId=world-101"))
        assertTrue(prompt.contains("experienceId=own-verified-memory"))
        assertFalse(prompt.contains("已自动发出"))
    }

    @Test fun noRealVenueOptionsOrExperiencesMeansNoFabricatedEventsOrActivityIds() {
        val prompt = ResidentWorldLifeOpportunities.render(
            locationCode = "resident-home:only-empty",
            locationActivities = emptyList(), itemActivities = "",
            sceneFacts = "", boredom = 99, sharingUrge = 0,
            verifiedShareContext = "")
        assertTrue(prompt.contains("没有登记可执行的公共地点活动"))
        assertFalse(prompt.contains("activityId=home_pace"))
        assertFalse(prompt.contains("incidentId="))
        assertFalse(prompt.contains("experienceId="))
        assertTrue(prompt.contains("数值不自动派发行动"))
    }
}
