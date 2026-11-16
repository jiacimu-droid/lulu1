package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DigitalWorldEnvironmentTest {
    @Test fun sharedWorldHasSeasonsAndNightWithoutRerollingOnModelCalls() {
        val winter = DigitalWorldEnvironment.snapshot(Instant.parse("2026-01-15T06:00:00Z"))
        val summer = DigitalWorldEnvironment.snapshot(Instant.parse("2026-07-15T06:00:00Z"))
        assertEquals("冬季", winter.season)
        assertEquals("夏季", summer.season)
        assertTrue(summer.temperatureCelsius > winter.temperatureCelsius)
        assertEquals(summer, DigitalWorldEnvironment.snapshot(Instant.parse("2026-07-15T06:00:00Z")))
        val night = DigitalWorldEnvironment.snapshot(Instant.parse("2026-07-15T16:00:00Z"))
        assertEquals("夜间", night.light)
        assertNotEquals(summer.observationKey, night.observationKey)
        assertTrue(night.context().contains("不是用户现实天气"))
    }

    @Test fun oneRealSceneRespondsToSeasonAndLightWithoutScriptedCharacterFeelings() {
        val spring = DigitalWorldLifeEventStore.openingSummary(
            "season_pixels", "江渡", "", Instant.parse("2026-04-15T06:00:00Z"))
        val autumn = DigitalWorldLifeEventStore.openingSummary(
            "season_pixels", "江渡", "", Instant.parse("2026-11-15T06:00:00Z"))
        assertTrue(spring.contains("浅绿"))
        assertTrue(autumn.contains("浅金"))
        assertNotEquals(spring, autumn)
        assertFalse(autumn.contains("像素叶"))
        val daylight = DigitalWorldLifeEventStore.openingSummary(
            "sun_patch", "江渡", "", Instant.parse("2026-11-15T06:00:00Z"))
        val night = DigitalWorldLifeEventStore.openingSummary(
            "sun_patch", "江渡", "", Instant.parse("2026-11-15T16:00:00Z"))
        assertTrue(daylight.contains("暖光"))
        assertTrue(night.contains("灯影"))
        assertFalse(night.contains("放晴"))
    }

    @Test fun temperatureChangesGraduallyInsteadOfRandomlyEachHour() {
        val start = Instant.parse("2026-10-10T00:00:00Z")
        for (hour in 1L..48L) {
            val before = DigitalWorldEnvironment.snapshot(start.plusSeconds((hour - 1) * 3600))
            val after = DigitalWorldEnvironment.snapshot(start.plusSeconds(hour * 3600))
            assertTrue(kotlin.math.abs(before.temperatureCelsius - after.temperatureCelsius) <= 2)
        }
    }
}
