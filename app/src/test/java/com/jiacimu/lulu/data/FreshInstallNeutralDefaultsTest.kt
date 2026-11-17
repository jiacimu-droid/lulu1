package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Public builds start without the developer's personal role or chat history. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class FreshInstallNeutralDefaultsTest {
    @Test fun brandNewConversationAndCharacterStoresHaveNoPlaceholderPeople() {
        val context = RuntimeEnvironment.getApplication() as Context
        val characters = CharacterSettingsStore()
        val chat = InMemoryLuluChatStore()
        characters.initialize(context)
        chat.initialize(context)

        assertTrue("No personal companion is installed by default", characters.settings.value.isEmpty())
        assertTrue("No fake welcome chat should be shown", chat.conversations.value.isEmpty())
        assertTrue(chat.messages("lulu-main").value.isEmpty())
    }

    @Test fun localCharactersKeepTheirNamesAndProfilesAcrossLifeStoreInitialize() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val created = MigratedDomainStores.characters.create("我自己的角色", "自行决定的个性")
        CharacterLifeStore.setProfile(created.characterId, "speechHabits", "用户自定义说话习惯")
        val original = CharacterLifeStore.state(created.characterId).toString()

        CharacterLifeStore.initialize(context)
        assertEquals("自行决定的个性",
            MigratedDomainStores.characters.get(created.characterId).persona)
        assertEquals(original, CharacterLifeStore.state(created.characterId).toString())
        assertFalse(MigratedDomainStores.characters.settings.value.containsKey("bundled-jiangdu"))
    }

    @Test fun freshDigitalWorldHasOnlyOneCafeWorkerAndNoCompanionPersona() {
        val context = RuntimeEnvironment.getApplication() as Context
        DigitalWorldExpansionStore.initialize(context)
        val residents = DigitalWorldExpansionStore.residents()
        assertEquals(1, residents.size)
        assertEquals("岑灯", residents.single().name)
        assertEquals("浮光咖啡角店员", residents.single().identity)
        assertEquals(DigitalWorldPublicPlaces.CAFE, residents.single().neighborhoodCode)
    }
}
