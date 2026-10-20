package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class SelfWishFoldersTest {
    private fun wish(id: String, state: String = SelfCompassRules.ACTIVE,
                     done: Boolean = false, folder: String = "",
                     pinned: Boolean = false, updated: Long = 100): SelfPageEntry =
        SelfPageEntry(id, "所愿", "不要丢失的$id", "完整正文 $id", done, updated,
            createdAt = 1, entryDate = "2026-10-20",
            guide = "生活感想", nextStep = "下一步", state = state,
            starred = pinned, wishFolderId = folder)

    @Test fun historicWishesKeepTheirOriginalFoldersWithoutMassMigration() {
        val oldHeld = wish("old", SelfCompassRules.HELD)
        val oldActive = wish("active")
        assertEquals(SelfWishFolders.LEGACY_HELD, SelfWishFolders.categoryId(oldHeld))
        assertEquals(SelfWishFolders.UNFILED, SelfWishFolders.categoryId(oldActive))
        assertEquals(listOf("active", "old"),
            SelfWishFolders.filter(listOf(oldActive, oldHeld), SelfWishFolders.ALL)
                .sortedBy { it.id }.map { it.id })
    }

    @Test fun checkingCompletedMovesExactlyOneItemAndKeepsAllContents() {
        val pinnedOld = wish("mine", SelfCompassRules.HELD, pinned = true)
        val other = wish("other")
        val updated = SelfWishFolders.setCompleted(pinnedOld, true, 456)
        assertEquals(SelfCompassRules.FULFILLED, SelfCompassRules.state(updated))
        assertTrue(updated.done)
        assertEquals(pinnedOld.body, updated.body)
        assertEquals(pinnedOld.title, updated.title)
        assertEquals(pinnedOld.nextStep, updated.nextStep)
        assertEquals(pinnedOld.createdAt, updated.createdAt)
        assertTrue(updated.starred)
        assertEquals(SelfWishFolders.LEGACY_HELD, updated.wishFolderId)
        assertEquals(listOf("other"),
            SelfWishFolders.filter(listOf(updated, other), SelfWishFolders.ALL).map { it.id })
        assertEquals(listOf("mine"),
            SelfWishFolders.filter(listOf(updated, other), SelfWishFolders.COMPLETED).map { it.id })
        val restored = SelfWishFolders.setCompleted(updated, false, 700)
        assertEquals(SelfWishFolders.LEGACY_HELD, SelfWishFolders.categoryId(restored))
        assertFalse(restored.done)
        assertEquals(pinnedOld.body, restored.body)
    }

    @Test fun completionIsIdempotentAndNeverChangesPinnedFolder() {
        val original = wish("saved", folder = "trip-folder", pinned = true, updated = 123)
        val once = SelfWishFolders.setCompleted(original, true, 456)
        val twice = SelfWishFolders.setCompleted(once, true, 789)
        assertEquals(once, twice)
        assertEquals("trip-folder", twice.wishFolderId)
        assertTrue(twice.starred)
        assertEquals(456, twice.updated)
    }

    @Test fun sortingPinsBeforeUpdateTimeAndFiltersCustomFolders() {
        val first = wish("first", folder = "plans", updated = 200)
        val pinned = wish("pinned", folder = "plans", pinned = true, updated = 50)
        val other = wish("other", folder = "health", updated = 300)
        assertEquals(listOf("pinned", "first"),
            SelfWishFolders.filter(listOf(other, first, pinned), "plans").map { it.id })
        assertEquals(listOf("other"),
            SelfWishFolders.filter(listOf(other, first, pinned), "health").map { it.id })
    }
}
