package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.core.MemoryPolicy
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class PersonalCharacterSettingsTest {
    @Before @After fun reconnectLifeStoreToCurrentRobolectricApplication() {
        // Robolectric recreates the application, while Kotlin objects can retain
        // a previous test's SharedPreferences instance in the same sandbox.
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterLifeStore, null)
    }

    @Test fun personalMemoryPolicySurvivesReloadAndDoesNotAlterOtherCharacters() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val repository = LocalMemoryRepository().apply { initialize(context) }
        val global = MemoryPolicy(10, 10, true)
        val personal = MemoryPolicy(3, 5, false)
        repository.updatePolicy("a", global)
        repository.updatePersonalPolicy("a", personal)
        assertEquals(personal, repository.observePolicy("a").first())
        assertEquals(global, repository.observePolicy("b").first())
        val reloaded = LocalMemoryRepository().apply { initialize(context) }
        assertEquals(personal, reloaded.observePolicy("a").first())
        val updatedGlobal = MemoryPolicy(15, 20, true)
        reloaded.updatePolicy("b", updatedGlobal)
        assertEquals(personal, reloaded.observePolicy("a").first())
        assertEquals(updatedGlobal, reloaded.observePolicy("b").first())
        reloaded.followGlobalPolicy("a")
        assertEquals(updatedGlobal, reloaded.observePolicy("a").first())
    }

    @Test fun expressionPreferenceIsScopedPersistedAndRefreshesLiveDefinition() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.characters.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        val a = MigratedDomainStores.characters.create("表达甲", "寡言但有自己的态度")
        val b = MigratedDomainStores.characters.create("表达乙", "明朗")
        val before = CharacterRuntime.definition(a.characterId)
        CharacterLifeStore.setExpressionPreference(a.characterId, "kaomoji", "restrained")
        assertFalse(before.hasSameConfiguration(CharacterRuntime.definition(a.characterId)))
        assertEquals("restrained", CharacterExpressionPreferences.selected(a.characterId, "kaomoji"))
        assertEquals("expressive", CharacterExpressionPreferences.selected(b.characterId, "kaomoji"))
        val disk = JSONObject(context.getSharedPreferences("lulu_character_life", 0)
            .getString(a.characterId, "{}")!!)
        assertEquals("restrained", disk.getJSONObject("expressionPreferences").getString("kaomoji"))
        // Speech rendering and a live voice snapshot both use this identity builder.
        assertTrue(CharacterSpeechIdentity.promptSection(a.characterId, includeConfigured = false)
            .contains("偏少"))
        reconnectLifeStoreToCurrentRobolectricApplication()
        CharacterLifeStore.initialize(context)
        assertEquals("restrained", CharacterExpressionPreferences.selected(a.characterId, "kaomoji"))
        CharacterLifeStore.clearHistory(a.characterId)
        assertEquals("restrained", CharacterExpressionPreferences.selected(a.characterId, "kaomoji"))
    }
}
