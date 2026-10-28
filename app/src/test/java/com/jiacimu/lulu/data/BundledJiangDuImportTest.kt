package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BundledJiangDuImportTest {
    @Test fun repositoryPersonaIsImportedAsAnIndependentEditableCharacterOnce() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val restored = MigratedDomainStores.characters.settings.value[id]
        assertNotNull(restored)
        assertEquals("江渡", restored!!.displayName)
        assertNotEquals("lulu", id)
        assertEquals(CharacterProfileSchema.jiangDuV24Persona, restored.persona)
        assertEquals(CharacterLifeForm.DIGITAL, DigitalLifeProfileStore.lifeForm(id))
        assertEquals(CharacterProfileSchema.jiangDuIdentity, CharacterIdentityStore.identities.value[id])
        val state = CharacterLifeStore.state(id)
        assertEquals(CharacterProfileSchema.jiangDuPresetVersion, state.getInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuV24Overrides.getValue("care"),
            state.getJSONObject("profile").getString("care"))
        assertEquals(CharacterProfileSchema.jiangDuOwnerRelationContextV24,
            state.getJSONObject("relationshipAgreements").getJSONObject("user").getString("details"))
        CharacterLifeStore.setProfile(id, "care", "我的自定义关怀设定")
        CharacterLifeStore.initialize(context)
        assertEquals("我的自定义关怀设定",
            CharacterLifeStore.state(id).getJSONObject("profile").getString("care"))
        assertEquals(1, MigratedDomainStores.characters.settings.value.keys.count { it == id })
    }

    @Test fun legacyLuluBecomesXingHeWithoutInheritingJiangDuOrLosingEdits() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)

        val friend = MigratedDomainStores.characters.settings.value["lulu"]!!
        assertEquals("星禾", friend.displayName)
        assertEquals(CharacterProfileSchema.xingHePersona, friend.persona)
        assertEquals(CharacterProfileSchema.xingHeIdentity,
            CharacterIdentityStore.identities.value["lulu"])
        val state = CharacterLifeStore.state("lulu")
        assertEquals(1, state.getInt("xingHeProfileVersion"))
        assertTrue(state.has("xingHeMigrationBackupV1"))
        assertFalse(state.has("jiangDuPresetVersion"))
        assertEquals("闺蜜", state.getJSONObject("relationshipAgreements")
            .getJSONObject("user").getString("title"))
        assertEquals(CharacterProfileSchema.xingHeProfile.getValue("speechHabits"),
            state.getJSONObject("profile").getString("speechHabits"))

        CharacterLifeStore.setProfile("lulu", "speechHabits", "我自己改过的说话方式")
        CharacterLifeStore.initialize(context)
        assertEquals("我自己改过的说话方式", CharacterLifeStore.state("lulu")
            .getJSONObject("profile").getString("speechHabits"))
        assertEquals("星禾", MigratedDomainStores.characters.settings.value["lulu"]!!.displayName)

        val jiangDu = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        assertNotEquals("lulu", jiangDu)
        assertEquals("江渡", MigratedDomainStores.characters.settings.value[jiangDu]!!.displayName)
        assertEquals(CharacterProfileSchema.jiangDuV24Persona,
            MigratedDomainStores.characters.settings.value[jiangDu]!!.persona)
    }
}
