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
    // Robolectric creates a fresh application/preferences sandbox for each test.
    // Drop the singleton's previous sandbox handle, without changing real fallback policy.
    @org.junit.Before fun resetPreferenceHandle() {
        ScopedModelSelections.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(ScopedModelSelections, null)
    }
    @org.junit.After fun releasePreferenceHandle() = resetPreferenceHandle()

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
            assertEquals(cheap.id, library.archiveIdFor(ModelUsage.Sleep))
        } finally {
            ScopedModelSelections.clearCharacterChatArchive(a)
            ScopedModelSelections.clearCharacterChatArchive(b)
        }
    }
    @Test fun personalFanficFollowsItsOwnCharacterChatUnlessExplicitlyOverridden() {
        val context = RuntimeEnvironment.getApplication() as Context
        ScopedModelSelections.initialize(context)
        val one = "fanfic-a-" + UUID.randomUUID()
        val other = "fanfic-b-" + UUID.randomUUID()
        val default = ModelArchive(id = "budget-" + UUID.randomUUID(),
            configurationId = "site", model = "budget")
        val premium = ModelArchive(id = "premium-" + UUID.randomUUID(),
            configurationId = "site", model = "premium")
        val library = ModelLibraryState(archives = listOf(default, premium),
            activeArchiveId = default.id, chatArchiveId = default.id)
        try {
            assertNull(ScopedModelSelections.personalTheaterOverrideId(one, library))
            assertEquals(default.id, ScopedModelSelections.selectedPersonalTheaterArchiveId(one, library))
            ScopedModelSelections.selectCharacterChatArchive(one, premium.id, library)
            assertEquals(premium.id, ScopedModelSelections.selectedPersonalTheaterArchiveId(one, library))
            assertEquals(default.id, ScopedModelSelections.selectedPersonalTheaterArchiveId(other, library))
            ScopedModelSelections.selectPersonalTheaterArchive(one, default.id, library)
            assertEquals(default.id, ScopedModelSelections.personalTheaterOverrideId(one, library))
            assertEquals(default.id, ScopedModelSelections.selectedPersonalTheaterArchiveId(one, library))
            assertEquals(premium.id, ScopedModelSelections.selectedCharacterChatArchiveId(one, library))
            assertEquals(default.id, ScopedModelSelections.selectedPersonalTheaterArchiveId(other, library))
            ScopedModelSelections.clearPersonalTheaterArchive(one)
            assertEquals(premium.id, ScopedModelSelections.selectedPersonalTheaterArchiveId(one, library))
            val afterDeletion = library.copy(archives = listOf(default), chatArchiveId = default.id)
            assertEquals(default.id,
                ScopedModelSelections.selectedPersonalTheaterArchiveId(one, afterDeletion))
        } finally {
            ScopedModelSelections.clearPersonalTheaterArchive(one)
            ScopedModelSelections.clearPersonalTheaterArchive(other)
            ScopedModelSelections.clearCharacterChatArchive(one)
            ScopedModelSelections.clearCharacterChatArchive(other)
        }
    }

    @Test fun normalPhoneAndSleepUseDifferentSourcesWithoutChangingThePerson() {
        val context = RuntimeEnvironment.getApplication() as Context
        ScopedModelSelections.initialize(context)
        val id = "phone-" + UUID.randomUUID()
        val fallback = ModelArchive(id = "budget", configurationId = "site", model = "budget")
        val foreground = ModelArchive(id = "premium", configurationId = "site", model = "premium")
        val library = ModelLibraryState(archives = listOf(fallback, foreground),
            activeArchiveId = fallback.id, chatArchiveId = fallback.id,
            sleepArchiveId = foreground.id)
        try {
            ScopedModelSelections.selectCharacterChatArchive(id, foreground.id, library)
            assertEquals(foreground.id,
                ScopedModelSelections.selectedPhoneArchiveId(id, sleepMode = false, library = library))
            assertEquals(fallback.id,
                ScopedModelSelections.selectedPhoneArchiveId(id, sleepMode = true, library = library))
            // Old saved independent sleep choice must not override the fallback.
            val altered = library.copy(sleepArchiveId = fallback.id)
            assertEquals(fallback.id,
                ScopedModelSelections.selectedPhoneArchiveId(id, sleepMode = true, library = altered))
            ScopedModelSelections.selectCharacterChatArchive(id, fallback.id, library)
            assertEquals(fallback.id,
                ScopedModelSelections.selectedPhoneArchiveId(id, sleepMode = false, library = library))
            assertEquals(fallback.id,
                ScopedModelSelections.selectedPhoneArchiveId(id, sleepMode = true, library = library))
        } finally {
            ScopedModelSelections.clearCharacterChatArchive(id)
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

    @Test fun residentModelStaysInResidentManagementAndWorldUsesStableFallback() {
        val context = RuntimeEnvironment.getApplication() as Context
        ScopedModelSelections.initialize(context)
        val prefs = context.getSharedPreferences("lulu_scoped_model_selections_v1", Context.MODE_PRIVATE)
        val keys = listOf("archive_resident_daily", "archive_resident_social",
            "archive_world_social_character", "archive_world_social_scene",
            "archive_world_life_episode", "archive_character_expression")
        val original = keys.associateWith { prefs.getString(it, null) }
        val resident = ModelArchive(id = "resident-cheap", configurationId = "api", model = "tiny")
        val chat = ModelArchive(id = "personal-premium", configurationId = "api", model = "premium")
        val library = ModelLibraryState(archives = listOf(resident, chat),
            chatArchiveId = chat.id, activeArchiveId = chat.id)
        try {
            val editor = prefs.edit()
            keys.forEach { editor.remove(it) }
            editor.putString("archive_resident_daily", resident.id)
            // Old hidden global routing preferences must no longer override
            // either the visible resident picker or the library fallback.
            editor.putString("archive_resident_social", chat.id)
                .putString("archive_world_social_character", resident.id)
                .putString("archive_world_social_scene", resident.id)
                .putString("archive_world_life_episode", resident.id)
                .putString("archive_character_expression", resident.id)
                .commit()
            assertEquals(resident.id, ScopedModelSelections.residentSocialArchiveId(library))
            assertEquals(resident.id, ScopedModelSelections.worldSocialArchiveId("resident:b", library))
            assertEquals(resident.id, ScopedModelSelections.worldLifeArchiveId("resident:b", library))
            assertEquals(chat.id, ScopedModelSelections.worldSceneArchiveId(library))
            assertEquals(chat.id, ScopedModelSelections.worldLifeArchiveId("character:a", library))
            assertEquals(chat.id, ScopedModelSelections.worldSocialArchiveId("character:a", library))
            assertNull(ScopedModelSelections.selectedArchiveId(ScopedModelSelections.EXPRESSION, library))
            val changedFallback = library.copy(activeArchiveId = resident.id)
            assertEquals(resident.id, ScopedModelSelections.worldSceneArchiveId(changedFallback))
            assertEquals(resident.id, ScopedModelSelections.worldSocialArchiveId("character:a", changedFallback))
            assertEquals(resident.id, ScopedModelSelections.residentSocialArchiveId(changedFallback))
        } finally {
            val editor = prefs.edit()
            original.forEach { (key, value) ->
                if (value == null) editor.remove(key) else editor.putString(key, value)
            }
            editor.commit()
        }
    }
}
