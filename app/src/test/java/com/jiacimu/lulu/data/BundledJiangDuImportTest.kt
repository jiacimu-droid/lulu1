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

    @Test fun existingBundledContactRepairsMissingDigitalLifeWithoutResettingItsIdentity() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)

        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val original = MigratedDomainStores.characters.settings.value[id]!!
        val originalLife = DigitalLifeProfileStore.get(id)
        val originalIdentity = CharacterIdentityStore.identities.value[id]
        val custom = original.copy(persona = "主人亲自修改过的人设，不允许被内置模板覆盖")
        try {
            MigratedDomainStores.characters.update(custom)
            // Simulate an interrupted/partial cross-store write: the one-time
            // contact import marker and contact survive, but life-form is lost.
            DigitalLifeProfileStore.remove(id)
            assertEquals(CharacterLifeForm.UNRESOLVED, DigitalLifeProfileStore.lifeForm(id))
            CharacterLifeStore.initialize(context)
            assertEquals(CharacterLifeForm.DIGITAL, DigitalLifeProfileStore.lifeForm(id))
            assertEquals(custom, MigratedDomainStores.characters.settings.value[id])
            assertEquals(originalIdentity, CharacterIdentityStore.identities.value[id])
            val repairedBirth = DigitalLifeProfileStore.birthAt(id)
            CharacterLifeStore.initialize(context)
            assertEquals(repairedBirth, DigitalLifeProfileStore.birthAt(id))

            // Deleting the contact is an owner's choice, not an interrupted
            // import. Reinitialization must not silently resurrect it.
            assertTrue(MigratedDomainStores.characters.delete(id))
            CharacterLifeStore.initialize(context)
            assertFalse(MigratedDomainStores.characters.settings.value.containsKey(id))
        } finally {
            MigratedDomainStores.characters.update(original)
            // Leave the test fixture with its original birth timestamp.
            DigitalLifeProfileStore.remove(id)
            DigitalLifeProfileStore.registerNewLife(id, original.displayName,
                originalLife.creatorName, originalLife.bornAt ?: java.time.Instant.now())
            CharacterLifeStore.initialize(context)
        }
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
        assertEquals(6, state.getInt("xingHeProfileVersion"))
        assertTrue(state.has("xingHeMigrationBackupV1"))
        assertFalse(state.has("jiangDuPresetVersion"))
        assertEquals("最好的闺蜜", state.getJSONObject("relationshipAgreements")
            .getJSONObject("user").getString("title"))
        assertEquals(CharacterProfileSchema.xingHeProfile.getValue("speechHabits"),
            state.getJSONObject("profile").getString("speechHabits"))
        assertEquals("natural",
            state.getJSONObject("expressionPreferences").getString("kaomoji"))

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
    @Test fun v2BestFriendWordingUpgradeRespectsManualStyleAndOtherCharacters() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val previousRole = MigratedDomainStores.characters.get("lulu")
        val previousState = JSONObject(CharacterLifeStore.state("lulu").toString())
        val otherId = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val otherBefore = JSONObject(CharacterLifeStore.state(otherId).toString())
        val otherPersona = MigratedDomainStores.characters.get(otherId).persona
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val archivedV2 = JSONObject(previousState.toString())
            val profile = archivedV2.getJSONObject("profile")
            CharacterProfileSchema.xingHeV2UpdatedFields.forEach { (field, old) ->
                profile.put(field, old)
            }
            profile.put("speechHabits", "用户亲自写的：简短、直白、不卖萌")
            archivedV2.getJSONObject("relationshipAgreements").getJSONObject("user")
                .put("details", CharacterProfileSchema.xingHeV2Friendship)
            archivedV2.put("xingHeProfileVersion", 2)
            archivedV2.remove("xingHeV2UpgradeBackup")
            save.invoke(CharacterLifeStore, "lulu", archivedV2)
            MigratedDomainStores.characters.update(previousRole.copy(
                displayName = "星禾", persona = CharacterProfileSchema.xingHeV2Persona))
            CharacterLifeStore.initialize(context)
            val upgraded = CharacterLifeStore.state("lulu")
            assertEquals(6, upgraded.getInt("xingHeProfileVersion"))
            assertTrue(upgraded.has("xingHeV2UpgradeBackup"))
            assertTrue(upgraded.has("xingHeV3ExpressionBackup"))
            assertEquals("natural", upgraded.getJSONObject("expressionPreferences").getString("kaomoji"))
            assertEquals(CharacterProfileSchema.xingHePersona,
                MigratedDomainStores.characters.get("lulu").persona)
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("perception"),
                upgraded.getJSONObject("profile").getString("perception"))
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("expression"),
                upgraded.getJSONObject("profile").getString("expression"))
            assertEquals("用户亲自写的：简短、直白、不卖萌",
                upgraded.getJSONObject("profile").getString("speechHabits"))
            assertEquals(CharacterProfileSchema.xingHeFriendship,
                upgraded.getJSONObject("relationshipAgreements").getJSONObject("user").getString("details"))
            assertEquals(otherPersona,
                MigratedDomainStores.characters.get(otherId).persona)
            assertEquals(otherBefore.toString(),
                CharacterLifeStore.state(otherId).toString())
            CharacterLifeStore.setProfile("lulu", "perception", "只按本人亲自编辑的理解方式")
            CharacterLifeStore.initialize(context)
            assertEquals("只按本人亲自编辑的理解方式",
                CharacterLifeStore.state("lulu").getJSONObject("profile").getString("perception"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", previousState)
            MigratedDomainStores.characters.update(previousRole)
            // The other persona was never part of this migration.
        }
    }

    @Test fun v2PersonalPersonaOrRelationshipEditsAreNotReset() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val previousRole = MigratedDomainStores.characters.get("lulu")
        val previousState = JSONObject(CharacterLifeStore.state("lulu").toString())
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val state = JSONObject(previousState.toString())
            state.put("xingHeProfileVersion", 2)
            state.getJSONObject("relationshipAgreements").getJSONObject("user")
                .put("details", "用户自己重写的朋友约定")
            state.getJSONObject("profile").put("perception", "")
            save.invoke(CharacterLifeStore, "lulu", state)
            MigratedDomainStores.characters.update(previousRole.copy(
                displayName = "星禾", persona = "用户自己写的完整人物简介"))
            CharacterLifeStore.initialize(context)
            val upgraded = CharacterLifeStore.state("lulu")
            assertEquals("用户自己写的完整人物简介",
                MigratedDomainStores.characters.get("lulu").persona)
            assertEquals("用户自己重写的朋友约定",
                upgraded.getJSONObject("relationshipAgreements").getJSONObject("user").getString("details"))
            assertEquals("", upgraded.getJSONObject("profile").getString("perception"))
            assertEquals(6, upgraded.getInt("xingHeProfileVersion"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", previousState)
            MigratedDomainStores.characters.update(previousRole)
        }
    }

    @Test fun v4WarmFriendshipUpgradePreservesCustomFieldsAndAllOtherCharacters() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val previous = JSONObject(CharacterLifeStore.state("lulu").toString())
        val originalRole = MigratedDomainStores.characters.get("lulu")
        val otherId = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val otherBefore = CharacterLifeStore.state(otherId).toString()
        val otherPersona = MigratedDomainStores.characters.get(otherId).persona
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val old = JSONObject(previous.toString())
            val profile = old.getJSONObject("profile")
            CharacterProfileSchema.xingHeV4ToneFields.forEach { (key, oldDefault) ->
                profile.put(key, oldDefault)
            }
            profile.put("speechHabits", "我自定义的闺蜜口吻")
            old.getJSONObject("relationshipAgreements").getJSONObject("user")
                .put("details", CharacterProfileSchema.xingHeV4Friendship)
            old.put("xingHeProfileVersion", 4).remove("xingHeV4WarmthBackup")
            save.invoke(CharacterLifeStore, "lulu", old)
            MigratedDomainStores.characters.update(originalRole.copy(
                displayName = "星禾", persona = CharacterProfileSchema.xingHeV4Persona))
            CharacterLifeStore.initialize(context)
            val upgraded = CharacterLifeStore.state("lulu")
            assertEquals(6, upgraded.getInt("xingHeProfileVersion"))
            assertTrue(upgraded.has("xingHeV4WarmthBackup"))
            assertEquals(CharacterProfileSchema.xingHePersona,
                MigratedDomainStores.characters.get("lulu").persona)
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("care"),
                upgraded.getJSONObject("profile").getString("care"))
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("conflict"),
                upgraded.getJSONObject("profile").getString("conflict"))
            assertEquals("我自定义的闺蜜口吻",
                upgraded.getJSONObject("profile").getString("speechHabits"))
            assertEquals(CharacterProfileSchema.xingHeFriendship,
                upgraded.getJSONObject("relationshipAgreements").getJSONObject("user")
                    .getString("details"))
            assertEquals(otherBefore, CharacterLifeStore.state(otherId).toString())
            assertEquals(otherPersona, MigratedDomainStores.characters.get(otherId).persona)
            CharacterLifeStore.initialize(context)
            assertEquals("我自定义的闺蜜口吻",
                CharacterLifeStore.state("lulu").getJSONObject("profile").getString("speechHabits"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", previous)
            MigratedDomainStores.characters.update(originalRole)
        }
    }

    @Test fun v5KnowledgeMigrationRemovesUnmetRoleNameButPreservesUserAuthoredIdentity() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val previous = JSONObject(CharacterLifeStore.state("lulu").toString())
        val originalIdentity = CharacterIdentityStore.identities.value["lulu"].orEmpty()
        val originalRole = MigratedDomainStores.characters.get("lulu")
        val otherId = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val otherBefore = CharacterLifeStore.state(otherId).toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val old = JSONObject(previous.toString())
            old.put("xingHeProfileVersion", 5).remove("xingHeV5KnowledgeBackup")
            old.getJSONObject("profile").put("social", CharacterProfileSchema.xingHeV5Social)
            save.invoke(CharacterLifeStore, "lulu", old)
            CharacterIdentityStore.set("lulu", CharacterProfileSchema.xingHeV5Identity)
            CharacterLifeStore.initialize(context)
            val updated = CharacterLifeStore.state("lulu")
            assertEquals(6, updated.getInt("xingHeProfileVersion"))
            assertTrue(updated.has("xingHeV5KnowledgeBackup"))
            assertEquals(CharacterProfileSchema.xingHeIdentity,
                CharacterIdentityStore.identities.value["lulu"])
            assertEquals(CharacterProfileSchema.xingHeProfile.getValue("social"),
                updated.getJSONObject("profile").getString("social"))
            assertFalse(CharacterRuntime.definition("lulu").promptSection().contains("江渡"))
            assertEquals(otherBefore, CharacterLifeStore.state(otherId).toString())
            // An owner-edited identity and a deliberately blank social field are authoritative.
            val manual = JSONObject(updated.toString())
            manual.put("xingHeProfileVersion", 5)
            manual.getJSONObject("profile").put("social", "")
            save.invoke(CharacterLifeStore, "lulu", manual)
            CharacterIdentityStore.set("lulu", "用户自己写的人物出身")
            CharacterLifeStore.initialize(context)
            assertEquals("用户自己写的人物出身", CharacterIdentityStore.identities.value["lulu"])
            assertEquals("", CharacterLifeStore.state("lulu")
                .getJSONObject("profile").getString("social"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", previous)
            CharacterIdentityStore.set("lulu", originalIdentity)
            MigratedDomainStores.characters.update(originalRole)
        }
    }

    @Test fun v3ExistingUserStyleChoiceSurvivesTheNaturalExpressionUpgrade() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val original = JSONObject(CharacterLifeStore.state("lulu").toString())
        val backupRole = MigratedDomainStores.characters.get("lulu")
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val prior = JSONObject(original.toString())
            prior.put("xingHeProfileVersion", 3)
            prior.remove("xingHeV3ExpressionBackup")
            prior.put("expressionPreferences", JSONObject()
                .put("kaomoji", "expressive").put("stickers", "restrained"))
            save.invoke(CharacterLifeStore, "lulu", prior)
            CharacterLifeStore.initialize(context)
            val migrated = CharacterLifeStore.state("lulu")
            assertEquals(6, migrated.getInt("xingHeProfileVersion"))
            assertEquals("expressive", migrated.getJSONObject("expressionPreferences").getString("kaomoji"))
            assertEquals("restrained", migrated.getJSONObject("expressionPreferences").getString("stickers"))
            assertEquals("星禾", MigratedDomainStores.characters.get("lulu").displayName)
            val actualGuide = CharacterExpressionContinuity.guide("lulu")
            assertTrue(actualGuide.contains("颜文字示例"))
            CharacterLifeStore.initialize(context)
            assertEquals("expressive", CharacterExpressionPreferences.selected("lulu", "kaomoji"))
            // The user changing it later takes effect immediately, without a new model call.
            CharacterLifeStore.setExpressionPreference("lulu", "kaomoji", "natural")
            assertFalse(CharacterExpressionContinuity.guide("lulu").contains("颜文字示例"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", original)
            MigratedDomainStores.characters.update(backupRole)
        }
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
            assertEquals(6, upgraded.getInt("xingHeProfileVersion"))
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
