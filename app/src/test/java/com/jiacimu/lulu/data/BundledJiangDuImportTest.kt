package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.json.JSONObject
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
        assertEquals(2, state.getInt("xingHeProfileVersion"))
        assertTrue(state.has("xingHeMigrationBackupV1"))
        assertFalse(state.has("jiangDuPresetVersion"))
        assertEquals("最好的闺蜜", state.getJSONObject("relationshipAgreements")
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
    @Test fun v1FriendshipUpgradeChangesOnlyUntouchedDefaults() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)

        val roleBefore = MigratedDomainStores.characters.settings.value["lulu"]!!
        val stateBefore = JSONObject(CharacterLifeStore.state("lulu").toString())
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val legacy = JSONObject(stateBefore.toString())
            val profile = legacy.getJSONObject("profile")
            profile.put("care", CharacterProfileSchema.xingHeV1UpdatedFields.getValue("care"))
            profile.put("conflict", CharacterProfileSchema.xingHeV1UpdatedFields.getValue("conflict"))
            profile.put("respect", "我亲自写过的友情边界")
            legacy.put("xingHeProfileVersion", 1)
            val relation = legacy.getJSONObject("relationshipAgreements").getJSONObject("user")
            relation.put("title", "闺蜜").put("details", CharacterProfileSchema.xingHeV1Friendship)
            save.invoke(CharacterLifeStore, "lulu", legacy)
            MigratedDomainStores.characters.update(roleBefore.copy(
                displayName = "星禾", persona = CharacterProfileSchema.xingHeV1Persona))

            CharacterLifeStore.initialize(context)
            val upgraded = CharacterLifeStore.state("lulu")
            assertEquals(2, upgraded.getInt("xingHeProfileVersion"))
            assertEquals(CharacterProfileSchema.xingHePersona,
                MigratedDomainStores.characters.get("lulu").persona)
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("care"),
                upgraded.getJSONObject("profile").getString("care"))
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("conflict"),
                upgraded.getJSONObject("profile").getString("conflict"))
            assertEquals("我亲自写过的友情边界",
                upgraded.getJSONObject("profile").getString("respect"))
            assertEquals("最好的闺蜜", upgraded.getJSONObject("relationshipAgreements")
                .getJSONObject("user").getString("title"))
            assertEquals(CharacterProfileSchema.xingHeFriendship,
                upgraded.getJSONObject("relationshipAgreements")
                    .getJSONObject("user").getString("details"))

            val edited = upgraded.getJSONObject("relationshipAgreements")
                .getJSONObject("user")
            edited.put("details", "我自己重新定义过的闺蜜关系")
            upgraded.put("xingHeProfileVersion", 1)
            save.invoke(CharacterLifeStore, "lulu", upgraded)
            CharacterLifeStore.initialize(context)
            assertEquals("我自己重新定义过的闺蜜关系",
                CharacterLifeStore.state("lulu").getJSONObject("relationshipAgreements")
                    .getJSONObject("user").getString("details"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", stateBefore)
            MigratedDomainStores.characters.update(roleBefore)
        }
    }

}
