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
}
