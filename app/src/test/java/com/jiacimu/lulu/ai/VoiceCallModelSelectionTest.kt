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

    @Test fun phoneFollowsChangedChatAndIndependentChoiceSurvivesReload() {
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
            store.selectArchive(first.id, ModelUsage.VoiceCall)
            assertFalse(store.library.value.voiceCallFollowsChat)
            assertEquals(first.id, ModelConnectionStore.create(context).selectedArchiveId(ModelUsage.VoiceCall))
            store.setVoiceCallFollowsChat(true)
            assertEquals(second.id, ModelConnectionStore.create(context).selectedArchiveId(ModelUsage.VoiceCall))
            assertEquals(first.id, store.library.value.voiceCallArchiveId)
            store.setVoiceCallFollowsChat(false)
            assertEquals(first.id, store.selectedArchiveId(ModelUsage.VoiceCall))
        } finally {
            prefs.edit().clear().apply()
            val editor = prefs.edit()
            before.forEach { (key, value) -> if (value is String) editor.putString(key, value) }
            editor.commit()
        }
    }

    @Test fun oldLibraryDefaultsToFollowingChatWithoutLosingPhoneChoice() {
        val context = RuntimeEnvironment.getApplication() as Context
        val prefs = context.getSharedPreferences("lulu_model_connection", 0)
        val old = prefs.getString("model_library_v2", null)
        try {
            prefs.edit().putString("model_library_v2", """{"configurations":[{"id":"c","name":"test","baseUrl":"https://example.test/v1","apiKey":"test"}],"archives":[{"id":"a","configurationId":"c","model":"chat"},{"id":"b","configurationId":"c","model":"phone"}],"activeArchiveId":"b","chatArchiveId":"a","voiceCallArchiveId":"b"}""").commit()
            val store = ModelConnectionStore.create(context)
            assertTrue(store.library.value.voiceCallFollowsChat)
            assertEquals("a", store.selectedArchiveId(ModelUsage.VoiceCall))
            assertEquals("b", store.library.value.voiceCallArchiveId)
        } finally { prefs.edit().putString("model_library_v2", old).commit() }
    }
}
