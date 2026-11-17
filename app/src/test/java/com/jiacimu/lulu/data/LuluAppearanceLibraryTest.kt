package com.jiacimu.lulu.data

import com.jiacimu.lulu.design.isJournal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The old nine bundled presets are gone; existing private recipes remain readable. */
class LuluAppearanceLibraryTest {
    private fun custom(id: String, motif: LuluAppearanceMotif = LuluAppearanceMotif.CLEAN) =
        LuluAppearanceCatalog.builtIns.first().copy(id = id, name = id, motif = motif, builtIn = false)

    @Test fun handcraftedJournalVisualsOnlyApplyToLegacyCustomMotif() {
        val journal = custom("my-linen", LuluAppearanceMotif.STITCH)
        val copy = journal.copy(id = "my-linen-copy")
        val manga = custom("my-manga", LuluAppearanceMotif.COMIC)
        assertTrue(journal.isJournal())
        assertTrue(copy.isJournal())
        assertFalse(manga.isJournal())
    }

    @Test fun switchingOneAppDoesNotChangeOtherApps() {
        val base = LuluAppearanceLibrary()
            .save(custom("handmade"))
            .save(custom("comic"))
            .select("Home", "handmade")
            .select("Chat", "comic")
        val next = base.select("Home", "paper")
        assertEquals("paper", next.active("Home").id)
        assertEquals("comic", next.active("Chat").id)
        assertEquals("paper", next.active("Reading").id)
        assertEquals("handmade", base.active("Home").id)
    }

    @Test fun recentlyUsedRecipesStayAvailableInLegacyLibrary() {
        val library = LuluAppearanceLibrary()
            .save(custom("one")).save(custom("two"))
            .select("Home", "one").select("Home", "two").select("Home", "one")
        assertEquals("one", library.recent.first())
        assertEquals(2, library.recent.size)
        assertEquals("two", library.select("Home", "two").active("Home").id)
    }

    @Test fun savedCopyDoesNotMutateItsSourceRecipe() {
        val original = LuluAppearanceCatalog.builtIns.first()
        val edited = original.copy(
            id = "custom", name = "自己写的", palette = original.palette.copy(accent = 0xFFB66A8AL),
            corner = 24, builtIn = false,
        )
        val library = LuluAppearanceLibrary().save(edited).select("Phone", edited.id)
        assertEquals(edited.id, library.active("Phone").id)
        assertEquals(original.palette.accent, library.find("paper")?.palette?.accent)
        assertEquals(24, library.active("Phone").corner)
    }

    @Test fun activeOrBuiltInRecipeCannotBeDeleted() {
        val library = LuluAppearanceLibrary()
            .save(custom("mine")).select("Home", "mine")
        assertEquals(library, library.remove("mine"))
        assertEquals(library, library.remove("paper"))
        val after = library.select("Home", "paper").remove("mine")
        assertFalse(after.custom.any { it.id == "mine" })
    }

    @Test fun unknownRecipeNeverDestroysExistingBinding() {
        val library = LuluAppearanceLibrary().select("Home", "paper")
        assertEquals(library, library.select("Home", "removed-theme"))
    }
}
