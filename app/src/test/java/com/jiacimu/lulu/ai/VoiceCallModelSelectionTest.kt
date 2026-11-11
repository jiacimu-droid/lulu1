package com.jiacimu.lulu.ai

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class VoiceCallModelSelectionTest {
    @Test fun removingAConfigurationRemovesItsArchivesBeforeAndAfterReload() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = ModelConnectionStore.create(context)
        val a = store.saveConfiguration(null, "remove-a", "https://a.test/v1", "test")
        val b = store.saveConfiguration(null, "keep-b", "https://b.test/v1", "test")
        val gone = store.addArchive(a.id, "phone-a")
        val kept = store.addArchive(b.id, "phone-b")
        store.selectArchive(gone.id, ModelUsage.VoiceCall)
        store.selectArchive(gone.id)
        store.deleteConfiguration(a.id)
        assertFalse(store.library.value.archives.any { it.id == gone.id })
        assertEquals(kept.id, store.selectedArchiveId(ModelUsage.VoiceCall))
        assertEquals("phone-b", store.resolveConnection(store.selectedArchiveId(ModelUsage.VoiceCall)).model)
        assertEquals(store.library.value, ModelConnectionStore.create(context).library.value)
    }

    @Test fun phoneAlwaysUsesCurrentChatArchiveIncludingWhenLegacyPhoneChoiceExists() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_model_connection", 0)
        val before = prefs.all
        try {
            prefs.edit().clear().commit()
            val store = ModelConnectionStore.create(context)
            val config = store.saveConfiguration(null, "test", "https://example.test/v1", "test-key")
            val first = store.addArchive(config.id, "first")
            val second = store.addArchive(config.id, "second")
            store.selectArchive(first.id, ModelUsage.Chat)
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.VoiceCall))
            store.selectArchive(second.id, ModelUsage.Chat)
            assertEquals(second.id, store.selectedArchiveId(ModelUsage.VoiceCall))
            assertEquals(second.id, ModelConnectionStore.create(context).selectedArchiveId(ModelUsage.VoiceCall))
            // Legacy callers passing VoiceCall now update the shared chat selection,
            // never re-enable an independent phone model.
            store.selectArchive(first.id, ModelUsage.VoiceCall)
            assertTrue(store.library.value.voiceCallFollowsChat)
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.Chat))
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.VoiceCall))
        } finally {
            prefs.edit().clear().apply()
            val editor = prefs.edit()
            before.forEach { (key, value) -> if (value is String) editor.putString(key, value) }
            editor.commit()
        }
    }

    @Test fun importingArchivesNeverChangesAnExplicitFallbackOrTheForegroundChat() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_model_connection", 0)
        val original = prefs.getString("model_library_v2", null)
        try {
            prefs.edit().remove("model_library_v2").commit()
            val store = ModelConnectionStore.create(context)
            val config = store.saveConfiguration(null, "main", "https://example.test/v1", "test")
            val first = store.addArchive(config.id, "cheap")
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.Sleep))
            val second = store.addArchive(config.id, "premium")
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.Sleep))
            store.selectArchive(second.id) // Explicit fallback choice only.
            store.selectArchive(first.id, ModelUsage.Chat) // Foreground is independent.
            store.addArchive(config.id, "newly-imported")
            store.addArchive(config.id, "cheap") // Duplicate import does not change either.
            assertEquals(second.id, store.selectedArchiveId(ModelUsage.Sleep))
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.Chat))
            store.selectArchive(first.id, ModelUsage.Sleep) // Old sleep slot is not used.
            assertEquals(second.id, store.selectedArchiveId(ModelUsage.Sleep))
            assertEquals(second.id, ModelConnectionStore.create(context)
                .selectedArchiveId(ModelUsage.Sleep))
            store.removeArchive(second.id)
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.Sleep))
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.Chat))
        } finally {
            val editor = prefs.edit()
            if (original == null) editor.remove("model_library_v2")
            else editor.putString("model_library_v2", original)
            editor.commit()
        }
    }

    @Test fun legacyIndependentPhoneSettingCannotOverrideChat() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_model_connection", 0)
        val old = prefs.getString("model_library_v2", null)
        try {
            prefs.edit().putString("model_library_v2", """{"configurations":[{"id":"c","name":"test","baseUrl":"https://example.test/v1","apiKey":"test"}],"archives":[{"id":"a","configurationId":"c","model":"chat"},{"id":"b","configurationId":"c","model":"phone"}],"activeArchiveId":"b","chatArchiveId":"a","voiceCallArchiveId":"b","voiceCallFollowsChat":false}""").commit()
            val store = ModelConnectionStore.create(context)
            assertFalse(store.library.value.voiceCallFollowsChat)
            assertEquals("a", store.selectedArchiveId(ModelUsage.VoiceCall))
            assertEquals("b", store.library.value.voiceCallArchiveId)
        } finally { prefs.edit().putString("model_library_v2", old).commit() }
    }
}
