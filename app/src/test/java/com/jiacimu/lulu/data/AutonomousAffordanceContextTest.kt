package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class AutonomousAffordanceContextTest {
    @Test fun offersOnlyExistingBookAndGroupsAndExistingWorldIdentifiers() {
        val text = AutonomousAffordanceContext.render(
            digital = true, location = "shared:reading_lounge",
            books = listOf(AutonomousAffordanceContext.Book("book-1", "真实书名")),
            groups = listOf("group-1" to "朋友群"),
            locationActivities = listOf("browse_reading" to "看书架"),
            publicPlaces = listOf("shared:courtyard" to "庭院"),
        )
        assertTrue(text.contains("readingBookId=book-1"))
        assertTrue(text.contains("groupId=group-1"))
        assertTrue(text.contains("activityId=browse_reading"))
        assertTrue(text.contains("location=shared:courtyard"))
        assertFalse(text.contains("book-2"))
    }

    @Test fun nonDigitalCharactersDoNotGetInventedWorldAccess() {
        val text = AutonomousAffordanceContext.render(
            digital = false, location = "", books = emptyList(), groups = emptyList(),
        )
        assertFalse(text.contains("worldAction=visit_public_place"))
        assertFalse(text.contains("readingBookId="))
        assertTrue(text.contains("solo_game"))
    }
}
