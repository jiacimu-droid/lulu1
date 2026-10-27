package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motive upgrade, phase 1: DriveState is pure arithmetic over real gaps
 * and counts. No store, no model call, no Android dependency, so these
 * tests run as plain JUnit.
 */
class DriveStateTest {

    private fun reading(input: DriveInput, kind: DriveKind): DriveReading =
        DriveState.snapshot(input).first { it.kind == kind }

    @Test
    fun valuesAreClampedToZeroAndOne() {
        val fresh = reading(DriveInput(), DriveKind.AFFILIATION)
        assertEquals(0.0, fresh.value, 0.0001)
        val saturated = reading(DriveInput(socialGapHours = 10_000), DriveKind.AFFILIATION)
        assertEquals(1.0, saturated.value, 0.0001)
        val careFull = reading(DriveInput(openItems = 100), DriveKind.CARE)
        assertEquals(1.0, careFull.value, 0.0001)
    }

    @Test
    fun affiliationUsesMostRecentSocialContact() {
        // A private chat an hour ago satisfies affiliation even though the
        // last call was days ago.
        val value = reading(
            DriveInput(socialGapHours = 1),
            DriveKind.AFFILIATION,
        ).value
        assertTrue(value < 0.1)
    }

    @Test
    fun curiosityIsSatisfiedByToolsAndByLearningAboutTheUser() {
        // Just searched the web: curiosity stays low even without reading.
        val byTool = reading(
            DriveInput(explorationGapHours = 2, explorationLabel = "用工具查/看", userInfoGapHours = 100),
            DriveKind.CURIOSITY,
        )
        assertTrue(byTool.value < 0.1)
        assertTrue(byTool.basis.contains("用工具查/看") || byTool.basis.contains("探索"))
        // Just learned something new about the user: curiosity stays low
        // even without any world exploration.
        val byUser = reading(
            DriveInput(explorationGapHours = 100, userInfoGapHours = 1),
            DriveKind.CURIOSITY,
        )
        assertTrue(byUser.value < 0.1)
        assertTrue(byUser.basis.contains("了解你在干什么"))
        // Neither for a long time: curiosity saturates.
        val stale = reading(
            DriveInput(explorationGapHours = 100, userInfoGapHours = 100),
            DriveKind.CURIOSITY,
        )
        assertEquals(1.0, stale.value, 0.0001)
    }

    @Test
    fun nightRestDominatesOtherDrives() {
        val readings = DriveState.snapshot(
            DriveInput(
                socialGapHours = 36,
                explorationGapHours = 24,
                userInfoGapHours = 24,
                shanghaiHour = 2,
            ),
        )
        val rest = readings.first { it.kind == DriveKind.REST }
        assertEquals(1.0, rest.value, 0.0001)
        assertEquals(DriveKind.REST, DriveState.strongest(readings)?.kind)
    }

    @Test
    fun daytimeRestOnlyReflectsRecentLoad() {
        val calm = reading(
            DriveInput(shanghaiHour = 14, recentInteractions = 0),
            DriveKind.REST,
        )
        assertEquals(0.0, calm.value, 0.0001)
        val busy = reading(
            DriveInput(shanghaiHour = 14, recentInteractions = 8),
            DriveKind.REST,
        )
        assertEquals(0.6, busy.value, 0.0001)
    }

    @Test
    fun careGrowsWithOpenItemsAndCaps() {
        assertEquals(0.0, reading(DriveInput(openItems = 0), DriveKind.CARE).value, 0.0001)
        assertEquals(0.5, reading(DriveInput(openItems = 3), DriveKind.CARE).value, 0.0001)
        assertEquals(1.0, reading(DriveInput(openItems = 6), DriveKind.CARE).value, 0.0001)
    }

    @Test
    fun autonomyGrowsWithUnspokenThoughts() {
        assertEquals(0.5, reading(DriveInput(unspokenThoughts = 3), DriveKind.AUTONOMY).value, 0.0001)
    }

    @Test
    fun strongThresholdIsConservative() {
        assertEquals(0.75, DriveState.STRONG_THRESHOLD, 0.0001)
        val rendered = DriveState.render(
            DriveState.snapshot(DriveInput(socialGapHours = 72)),
        )
        assertTrue(rendered.contains("亲近 100"))
        assertTrue(rendered.contains("仅作参考"))
    }
}
