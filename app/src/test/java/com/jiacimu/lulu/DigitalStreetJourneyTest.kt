package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class DigitalStreetJourneyTest {
    @Test fun discoveryAdvancesByPhysicallyConfirmedLandmarksNotTimeOrDialogue() {
        assertEquals("old-book-stall", DigitalStreetJourney.nextClue(emptySet()))
        assertEquals(setOf("old-book-stall"), DigitalStreetJourney.visibleClues(emptySet()))
        val first = setOf("old-book-stall")
        assertEquals("wishing-tree", DigitalStreetJourney.nextClue(first))
        assertEquals(setOf("old-book-stall", "wishing-tree"), DigitalStreetJourney.visibleClues(first))
        assertFalse(DigitalStreetJourney.isMementoUnlocked(first))
        val second = first + "wishing-tree"
        assertEquals("viewpoint", DigitalStreetJourney.nextClue(second))
        assertFalse(DigitalStreetJourney.isMementoUnlocked(second))
        val third = second + "viewpoint"
        assertNull(DigitalStreetJourney.nextClue(third))
        assertTrue(DigitalStreetJourney.isMementoUnlocked(third))
    }

    @Test fun mementosAreDistinctForActualCompanionPartiesAndNeverInvented() {
        assertEquals("solo", DigitalStreetJourney.participantKey(emptyList()))
        assertEquals("jiang|resident", DigitalStreetJourney.participantKey(
            listOf("resident", "jiang", "jiang")))
        val note = DigitalStreetJourney.mementoText(
            listOf("我", "江渡"), "2026-11-16")
        assertTrue(note.contains("江渡"))
        assertTrue(note.contains("旧书摊、许愿树与观景台"))
        assertFalse(DigitalStreetJourney.mementoText(
            listOf("我"), "2026-11-16").contains("江渡"))
    }

    @Test fun unrelatedMarkersCannotUnlockOrAdvanceTravelCard() {
        val stray = setOf("wishing-tree", "viewpoint", "unknown")
        assertEquals("old-book-stall", DigitalStreetJourney.nextClue(stray))
        assertFalse(DigitalStreetJourney.isMementoUnlocked(stray))
    }
}
