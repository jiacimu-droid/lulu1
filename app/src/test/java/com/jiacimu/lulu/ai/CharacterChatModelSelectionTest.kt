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

    @Test fun residentAndWorldModelFallbacksStayIsolatedAndConfigurable() {
        val context = RuntimeEnvironment.getApplication() as Context
        ScopedModelSelections.initialize(context)
        val prefs = context.getSharedPreferences("lulu_scoped_model_selections_v1", Context.MODE_PRIVATE)
        val keys = listOf("archive_resident_daily", "archive_resident_social",
            "archive_world_social_character", "archive_world_social_scene", "archive_world_life_episode")
        val original = keys.associateWith { prefs.getString(it, null) }
        val cheap = ModelArchive(id = "resident-cheap", configurationId = "api", model = "tiny")
        val premium = ModelArchive(id = "personal-premium", configurationId = "api", model = "premium")
        val library = ModelLibraryState(archives = listOf(cheap, premium), chatArchiveId = premium.id)
        try {
            val blank = prefs.edit()
            keys.forEach(blank::remove)
            blank.putString("archive_resident_daily", cheap.id).commit()
            assertEquals(cheap.id, ScopedModelSelections.residentSocialArchiveId(library))
            assertEquals(cheap.id, ScopedModelSelections.worldSocialArchiveId("resident:b", library))
            assertEquals(cheap.id, ScopedModelSelections.worldSceneArchiveId(library))
            assertEquals(cheap.id, ScopedModelSelections.worldLifeArchiveId("resident:b", library))
            assertEquals(premium.id, ScopedModelSelections.worldSocialArchiveId("character:a", library))
            assertEquals(premium.id, ScopedModelSelections.worldLifeArchiveId("character:a", library))
            prefs.edit().putString("archive_world_social_character", cheap.id)
                .putString("archive_world_social_scene", premium.id)
                .putString("archive_world_life_episode", premium.id).commit()
            assertEquals(cheap.id, ScopedModelSelections.worldSocialArchiveId("character:a", library))
            assertEquals(premium.id, ScopedModelSelections.worldSceneArchiveId(library))
            assertEquals(premium.id, ScopedModelSelections.worldLifeArchiveId("resident:b", library))
        } finally {
            val editor = prefs.edit()
            original.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            editor.commit()
        }
    }
}
