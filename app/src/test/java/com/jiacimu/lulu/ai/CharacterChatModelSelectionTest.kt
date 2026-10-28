package com.jiacimu.lulu.ai

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterChatModelSelectionTest {
    @Test fun switchingAInItsChatWindowCannotChangeB() {
        val context = RuntimeEnvironment.getApplication() as Context
        ScopedModelSelections.initialize(context)
        val a = "a-" + UUID.randomUUID()
        val b = "b-" + UUID.randomUUID()
        val cheap = ModelArchive(id = "cheap", configurationId = "site", model = "cheap")
        val premium = ModelArchive(id = "premium", configurationId = "site", model = "premium")
        val library = ModelLibraryState(archives = listOf(cheap, premium),
            chatArchiveId = cheap.id, activeArchiveId = cheap.id, sleepArchiveId = premium.id)
        try {
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(a, library))
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(b, library))
            ScopedModelSelections.selectCharacterChatArchive(a, premium.id, library)
            assertEquals(premium.id, ScopedModelSelections.selectedCharacterChatArchiveId(a, library))
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(b, library))
            ScopedModelSelections.selectCharacterChatArchive(b, cheap.id, library)
            val newDefault = library.copy(chatArchiveId = premium.id)
            assertEquals(premium.id, ScopedModelSelections.selectedCharacterChatArchiveId(a, newDefault))
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(b, newDefault))
            ScopedModelSelections.clearCharacterChatArchive(a)
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(a, library))
            assertEquals(premium.id, library.archiveIdFor(ModelUsage.Sleep))
        } finally {
            ScopedModelSelections.clearCharacterChatArchive(a)
            ScopedModelSelections.clearCharacterChatArchive(b)
        }
    }
    @Test fun missingOldArchiveFallsBackInsteadOfAffectingOtherCharacter() {
        val context = RuntimeEnvironment.getApplication() as Context
        ScopedModelSelections.initialize(context)
        val a = "a-" + UUID.randomUUID()
        val b = "b-" + UUID.randomUUID()
        val cheap = ModelArchive(id = "cheap", configurationId = "site", model = "cheap")
        val premium = ModelArchive(id = "premium", configurationId = "site", model = "premium")
        val library = ModelLibraryState(archives = listOf(cheap,premium),chatArchiveId = cheap.id)
        try {
            ScopedModelSelections.selectCharacterChatArchive(a, premium.id, library)
            ScopedModelSelections.selectCharacterChatArchive(b, cheap.id, library)
            val missing = library.copy(archives = listOf(cheap))
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(a, missing))
            assertEquals(cheap.id, ScopedModelSelections.selectedCharacterChatArchiveId(b, missing))
        } finally {
            ScopedModelSelections.clearCharacterChatArchive(a)
            ScopedModelSelections.clearCharacterChatArchive(b)
        }
    }
}
