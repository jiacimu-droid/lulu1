package com.jiacimu.lulu.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.jiacimu.lulu.design.isJournal

class LuluAppearanceLibraryTest {
    @Test
    fun handcraftedJournalVisualsOnlyApplyToJournalFamily() {
        val journal = LuluAppearanceCatalog.builtIns.first { it.id == "journal" }
        val copy = journal.copy(id = "my-linen", builtIn = false)
        val manga = LuluAppearanceCatalog.builtIns.first { it.id == "manga" }
        assertTrue(journal.isJournal())
        assertTrue(copy.isJournal())
        assertFalse(manga.isJournal())
    }

    @Test
    fun switchingOneAppDoesNotChangeOtherApps() {
        val base = LuluAppearanceLibrary()
            .select("Home", "journal")
            .select("Chat", "phantom")
        val next = base.select("Home", "han")
        assertEquals("han", next.active("Home").id)
        assertEquals("phantom", next.active("Chat").id)
        assertEquals("paper", next.active("Reading").id)
        assertEquals("journal", base.active("Home").id)
    }

    @Test
    fun recentlyUsedRecipesStayAvailableToSwitchBack() {
        val library = LuluAppearanceLibrary()
            .select("Home", "journal")
            .select("Home", "phantom")
            .select("Home", "journal")
        assertEquals("journal", library.recent.first())
        assertEquals(2, library.recent.size)
        assertEquals("phantom", library.select("Home", "phantom").active("Home").id)
    }

    @Test
    fun savedCopyDoesNotMutateItsSourceRecipe() {
        val original = LuluAppearanceCatalog.builtIns.first { it.id == "gothic" }
        val edited = original.copy(
            id = "my-gothic",
            name = "午夜玫瑰 · 浅色版",
            palette = original.palette.copy(accent = 0xFFB66A8AL),
            corner = 24,
            builtIn = false,
        )
        val library = LuluAppearanceLibrary().save(edited).select("Phone", edited.id)
        assertEquals(edited.id, library.active("Phone").id)
        assertEquals(original.palette.accent, library.find("gothic")?.palette?.accent)
        assertEquals(24, library.active("Phone").corner)
        assertTrue(library.custom.any { it.id == "my-gothic" })
    }

    @Test
    fun activeOrBuiltInRecipeCannotBeDeleted() {
        val edited = LuluAppearanceCatalog.builtIns.first().copy(
            id = "my-journal", name = "自定义", builtIn = false,
        )
        val library = LuluAppearanceLibrary()
            .save(edited).select("Home", "my-journal")
        assertEquals(library, library.remove("my-journal"))
        assertEquals(library, library.remove("journal"))
        val afterChange = library.select("Home", "paper").remove("my-journal")
        assertFalse(afterChange.custom.any { it.id == "my-journal" })
        assertEquals("paper", afterChange.active("Home").id)
    }

    @Test
    fun unknownRecipeNeverDestroysAnExistingBinding() {
        val library = LuluAppearanceLibrary().select("Home", "han")
        assertEquals(library, library.select("Home", "uninstalled-theme"))
        assertEquals("han", library.active("Home").id)
    }
}
