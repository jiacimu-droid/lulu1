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
    @Test fun authoredDialogueExamplesAreSeededOnceForExactlyTheTwoExistingCharacters() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)

        val jiangId = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val xingId = "lulu"
        val jiangBefore = JSONObject(CharacterLifeStore.state(jiangId).toString())
        val xingBefore = JSONObject(CharacterLifeStore.state(xingId).toString())
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        fun replace(id: String, original: JSONObject) {
            val changed = JSONObject(original.toString())
            val profile = changed.optJSONObject("profile") ?: JSONObject()
            profile.remove("dialogueExamples")
            changed.put("profile", profile)
            save.invoke(CharacterLifeStore, id, changed)
        }
        try {
            // An existing installation upgraded from before examples existed.
            replace(jiangId, jiangBefore)
            replace(xingId, xingBefore)
            CharacterLifeStore.initialize(context)

            val jiang = CharacterLifeStore.state(jiangId)
                .getJSONObject("profile").getString("dialogueExamples")
            val xing = CharacterLifeStore.state(xingId)
                .getJSONObject("profile").getString("dialogueExamples")
            assertEquals(CharacterProfileSchema.jiangDuDialogueExamples, jiang)
            assertEquals(CharacterProfileSchema.xingHeDialogueExamples, xing)
            assertNotEquals(jiang, xing)
            assertTrue(jiang.contains("【数字世界见面·靠近"))
            assertTrue(xing.contains("星禾："))
            assertFalse(jiang.contains("星禾："))
            assertFalse(xing.contains("江渡："))

            // Actual model-facing speech section gets the individual example,
            // not a new real memory or biography or the other person's style.
            val jiangVoice = CharacterSpeechIdentity.promptSection(jiangId)
            val xingVoice = CharacterSpeechIdentity.promptSection(xingId)
            assertTrue(jiangVoice.contains(CharacterProfileSchema.jiangDuDialogueExamples))
            assertFalse(jiangVoice.contains("星禾："))
            assertTrue(xingVoice.contains("星禾：哟，稀客！"))
            assertFalse(xingVoice.contains("【数字世界见面·选画"))
            assertFalse(CharacterLifeStore.profileContext(jiangId).contains("【数字世界见面·选画"))
            assertFalse(CharacterLifeStore.profileContext(xingId).contains("星禾："))

            CharacterLifeStore.setProfile(jiangId, "dialogueExamples", "主人自己写的独家样例")
            CharacterLifeStore.setProfile(xingId, "dialogueExamples", "") // intentionally cleared
            CharacterLifeStore.initialize(context)
            assertEquals("主人自己写的独家样例", CharacterLifeStore.state(jiangId)
                .getJSONObject("profile").getString("dialogueExamples"))
            assertEquals("", CharacterLifeStore.state(xingId)
                .getJSONObject("profile").getString("dialogueExamples"))
        } finally {
            save.invoke(CharacterLifeStore, jiangId, jiangBefore)
            save.invoke(CharacterLifeStore, xingId, xingBefore)
        }
    }

    @Test fun jiangDuV26MigratesWholeStockProfileAndInjectsCognitiveHierarchyAndOneScene() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val original = JSONObject(CharacterLifeStore.state(id).toString())
        val character = MigratedDomainStores.characters.get(id)
        val identity = CharacterIdentityStore.get(id)
        val otherBefore = CharacterLifeStore.state("lulu").toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val old = JSONObject(original.toString()).put("jiangDuPresetVersion", 25)
            val profile = old.getJSONObject("profile")
            profile.remove("cognitiveStyle")
            CharacterProfileSchema.jiangDuV25StockFields.forEach { (key, value) ->
                profile.put(key, value)
            }
            profile.put("dialogueExamples", CharacterProfileSchema.jiangDuDialogueExamplesV25Reviewed)
            save.invoke(CharacterLifeStore, id, old)
            MigratedDomainStores.characters.update(character.copy(
                persona = CharacterProfileSchema.jiangDuV24Persona))
            CharacterLifeStore.initialize(context)
            val upgraded = CharacterLifeStore.state(id)
            val actual = upgraded.getJSONObject("profile")
            assertEquals(29, upgraded.getInt("jiangDuPresetVersion"))
            CharacterProfileSchema.jiangDuV29Overrides.forEach { (key, value) ->
                assertEquals("Factory field should migrate: $key", value, actual.getString(key))
            }
            assertEquals(identity, CharacterIdentityStore.get(id))
            listOf("relationshipAgreements", "socialNames", "intention").forEach { key ->
                assertEquals("Unrelated state retained: $key",
                    old.opt(key)?.toString(), upgraded.opt(key)?.toString())
            }
            val role = CharacterRuntime.definition(id).persona
            assertTrue(role.contains("Ni主导"))
            assertTrue(role.contains("Fe辅助"))
            assertTrue(role.contains("Ti第三"))
            assertTrue(role.contains("Se第四"))
            assertTrue(role.contains("关心用户的未来"))
            assertTrue(role.contains("主动记挂、推进并关心结果"))
            assertTrue(role.contains("兑现双方仍认可的约定"))
            assertFalse(CharacterRuntime.definition("lulu").persona.contains("Ni 主导"))
            assertEquals(otherBefore, CharacterLifeStore.state("lulu").toString())

            val sample = actual.getString("dialogueExamples")
            val body = sample.substringAfter("\n").substringAfter("\n")
            assertTrue("One scene within 500–1000 non-whitespace characters",
                body.count { !it.isWhitespace() } in 500..1000)
            assertEquals(1, Regex("【数字世界见面").findAll(sample).count())
            assertFalse(sample.contains("【学习时"))
            assertFalse(sample.contains("哎哟，书又欺负你了"))
            assertTrue(sample.contains("现在想让你疼我"))
            assertTrue(sample.contains("“这样？”"))
            assertTrue(CharacterSpeechIdentity.promptSection(id).contains(sample))
            assertFalse(CharacterLifeStore.profileContext(id).contains(sample))
            val cognitiveField = CharacterProfileSchema.groupedFields()
                .single { it.first == "认知结构" }.second.single()
            assertEquals("cognitiveStyle", cognitiveField.key)
            assertTrue(CharacterLifeStore.profileContext(id).contains("【认知结构】"))

            // Both previously shipped sample revisions migrate through the SAME
            // editable field; the old scenarios never get appended to the new scene.
            for (previous in listOf(CharacterProfileSchema.jiangDuDialogueExamplesV24,
                    CharacterProfileSchema.jiangDuDialogueExamplesV25)) {
                CharacterLifeStore.setProfile(id, "dialogueExamples", previous)
                CharacterLifeStore.syncBundledExpressionPresets()
                assertEquals(sample, CharacterLifeStore.state(id).getJSONObject("profile")
                    .getString("dialogueExamples"))
            }

            val custom = JSONObject(old.toString())
            custom.getJSONObject("profile").put("care", "主人亲自编辑的照顾方式")
                .put("perception", "").put("cognitiveStyle", "")
                .put("dialogueExamples", "主人只要自己的示例")
            save.invoke(CharacterLifeStore, id, custom)
            MigratedDomainStores.characters.update(character.copy(persona = "本人手写的核心人格"))
            CharacterLifeStore.initialize(context)
            val personalized = CharacterLifeStore.state(id).getJSONObject("profile")
            assertEquals("主人亲自编辑的照顾方式", personalized.getString("care"))
            assertEquals("", personalized.getString("perception"))
            assertEquals("", personalized.getString("cognitiveStyle"))
            assertEquals("主人只要自己的示例", personalized.getString("dialogueExamples"))
            assertEquals("本人手写的核心人格", MigratedDomainStores.characters.get(id).persona)
            assertEquals(CharacterProfileSchema.jiangDuV29Overrides.getValue("motives"),
                personalized.getString("motives"))
            CharacterLifeStore.setProfile(id, "cognitiveStyle", "用户自己写的功能主次")
            CharacterLifeStore.initialize(context)
            assertEquals("用户自己写的功能主次", CharacterLifeStore.state(id)
                .getJSONObject("profile").getString("cognitiveStyle"))
            assertEquals(otherBefore, CharacterLifeStore.state("lulu").toString())
        } finally {
            save.invoke(CharacterLifeStore, id, original)
            MigratedDomainStores.characters.update(character)
        }
    }

    @Test fun onlyOldStockXingHeDialogueAndStyleAreUpgradedAndManualChangesSurvive() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterIdentityStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val xingId = "lulu"
        val jiangId = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val before = JSONObject(CharacterLifeStore.state(xingId).toString())
        val jiangBefore = JSONObject(CharacterLifeStore.state(jiangId).toString())
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val legacy = JSONObject(before.toString())
            legacy.getJSONObject("profile")
                .put("dialogueExamples", CharacterProfileSchema.xingHeDialogueExamplesV1)
                .put("speechHabits", CharacterProfileSchema.xingHeSpeechHabitsV6)
            save.invoke(CharacterLifeStore, xingId, legacy)
            CharacterLifeStore.initialize(context)
            val upgraded = CharacterLifeStore.state(xingId).getJSONObject("profile")
            assertEquals(CharacterProfileSchema.xingHeDialogueExamples,
                upgraded.getString("dialogueExamples"))
            assertEquals(CharacterProfileSchema.xingHeSpeechHabitsV9,
                upgraded.getString("speechHabits"))
            assertTrue(upgraded.getString("dialogueExamples").contains("星禾："))
            assertFalse(CharacterLifeStore.profileContext(xingId)
                .contains("星禾：啊啊啊"))
            assertEquals(jiangBefore.toString(), CharacterLifeStore.state(jiangId).toString())

            // Already-installed v7/V2 bundled defaults must upgrade as well.
            val shipped = JSONObject(before.toString())
            shipped.getJSONObject("profile")
                .put("speechHabits", CharacterProfileSchema.xingHeSpeechHabitsV7)
                .put("dialogueExamples", CharacterProfileSchema.xingHeDialogueExamplesV2)
            save.invoke(CharacterLifeStore, xingId, shipped)
            CharacterLifeStore.initialize(context)
            val refreshed = CharacterLifeStore.state(xingId).getJSONObject("profile")
            assertEquals(CharacterProfileSchema.xingHeSpeechHabitsV9, refreshed.getString("speechHabits"))
            assertEquals(CharacterProfileSchema.xingHeDialogueExamples, refreshed.getString("dialogueExamples"))

            // The v8/currently-shipped compound default also migrates, while
            // retaining exactly the user's independent identity and other fields.
            val previous = JSONObject(before.toString())
            previous.getJSONObject("profile")
                .put("speechHabits", CharacterProfileSchema.xingHeSpeechHabitsV8)
                .put("dialogueExamples", CharacterProfileSchema.xingHeDialogueExamplesV3)
            save.invoke(CharacterLifeStore, xingId, previous)
            CharacterLifeStore.initialize(context)
            val migratedV8 = CharacterLifeStore.state(xingId).getJSONObject("profile")
            assertEquals(CharacterProfileSchema.xingHeSpeechHabitsV9,
                migratedV8.getString("speechHabits"))
            assertEquals(CharacterProfileSchema.xingHeDialogueExamples,
                migratedV8.getString("dialogueExamples"))
            assertEquals(jiangBefore.toString(), CharacterLifeStore.state(jiangId).toString())
            CharacterLifeStore.initialize(context)
            assertEquals(CharacterProfileSchema.xingHeDialogueExamples,
                CharacterLifeStore.state(xingId).getJSONObject("profile").getString("dialogueExamples"))

            // Owner's chosen tone and authored dialogue take precedence over
            // this or any future bundled preset, including intentional blanks.
            CharacterLifeStore.setProfile(xingId, "dialogueExamples", "我写的星禾特有对话")
            CharacterLifeStore.setProfile(xingId, "speechHabits", "开朗也可以很简练")
            CharacterLifeStore.initialize(context)
            assertEquals("我写的星禾特有对话", CharacterLifeStore.state(xingId)
                .getJSONObject("profile").getString("dialogueExamples"))
            assertEquals("开朗也可以很简练", CharacterLifeStore.state(xingId)
                .getJSONObject("profile").getString("speechHabits"))
            CharacterLifeStore.setProfile(xingId, "dialogueExamples", "")
            CharacterLifeStore.setProfile(xingId, "speechHabits", "")
            CharacterLifeStore.initialize(context)
            assertEquals("", CharacterLifeStore.state(xingId)
                .getJSONObject("profile").getString("dialogueExamples"))
            assertEquals("", CharacterLifeStore.state(xingId)
                .getJSONObject("profile").getString("speechHabits"))
        } finally {
            save.invoke(CharacterLifeStore, xingId, before)
        }
    }

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
        assertEquals(CharacterProfileSchema.jiangDuV29Persona, restored.persona)
        assertEquals(CharacterLifeForm.DIGITAL, DigitalLifeProfileStore.lifeForm(id))
        assertEquals(CharacterProfileSchema.jiangDuIdentity, CharacterIdentityStore.identities.value[id])
        val state = CharacterLifeStore.state(id)
        assertEquals(CharacterProfileSchema.jiangDuPresetVersion, state.getInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuApprovedOverrides.getValue("care"),
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
        assertTrue("主人已经允许星禾主动联系", friend.contactPolicy.enabled)
        assertTrue("主人已经允许星禾主动来电", friend.contactPolicy.proactiveCallsEnabled)
        assertFalse("旧露露的安静时间不应继续限制星禾", friend.contactPolicy.quietHoursEnabled)
        val jiangContact = MigratedDomainStores.characters.get(CharacterLifeStore.BUNDLED_JIANG_DU_ID)
        assertTrue(jiangContact.contactPolicy.proactiveCallsEnabled)

        CharacterLifeStore.setProfile("lulu", "speechHabits", "我自己改过的说话方式")
        CharacterLifeStore.initialize(context)
        assertEquals("我自己改过的说话方式", CharacterLifeStore.state("lulu")
            .getJSONObject("profile").getString("speechHabits"))
        assertEquals("星禾", MigratedDomainStores.characters.settings.value["lulu"]!!.displayName)

        val jiangDu = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        assertNotEquals("lulu", jiangDu)
        assertEquals("江渡", MigratedDomainStores.characters.settings.value[jiangDu]!!.displayName)
        assertEquals(CharacterProfileSchema.jiangDuV29Persona,
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
            old.getJSONObject("relationshipAgreements").getJSONObject("user")
                .put("details", CharacterProfileSchema.xingHeV5Friendship)
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
            assertEquals(CharacterProfileSchema.xingHeFriendship,
                updated.getJSONObject("relationshipAgreements").getJSONObject("user").getString("details"))
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

    @Test fun v27UpgradesShippedV26ButPreservesManualFieldsAndScene() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val original = JSONObject(CharacterLifeStore.state(id).toString())
        val role = MigratedDomainStores.characters.get(id)
        val other = CharacterLifeStore.state("lulu").toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            fun seed(custom: Boolean) {
                val root = JSONObject(original.toString()).put("jiangDuPresetVersion", 26)
                val profile = root.getJSONObject("profile")
                CharacterProfileSchema.jiangDuV26Overrides.forEach { (key, value) -> profile.put(key, value) }
                listOf("enneagram", "traits", "attachment").forEach { profile.remove(it) }
                profile.put("dialogueExamples", CharacterProfileSchema.jiangDuDialogueExamplesV26)
                if (custom) profile.put("values", "自己的价值").put("traits", "")
                    .put("dialogueExamples", "自己的示例")
                save.invoke(CharacterLifeStore, id, root)
                MigratedDomainStores.characters.update(role.copy(persona =
                    if (custom) "自己的核心人格" else CharacterProfileSchema.jiangDuV26Persona))
            }
            seed(false)
            CharacterLifeStore.initialize(context)
            val profile = CharacterLifeStore.state(id).getJSONObject("profile")
            CharacterProfileSchema.jiangDuV29Overrides.forEach { (key, value) ->
                assertEquals(key, value, profile.getString(key))
            }
            assertEquals(CharacterProfileSchema.jiangDuV29Persona,
                MigratedDomainStores.characters.get(id).persona)
            assertEquals(CharacterProfileSchema.jiangDuDialogueExamples, profile.getString("dialogueExamples"))
            assertTrue(CharacterRuntime.definition(id).persona.contains("张扬"))
            assertTrue(CharacterLifeStore.profileContext(id).contains("安全型依恋"))
            assertEquals(other, CharacterLifeStore.state("lulu").toString())
            seed(true)
            CharacterLifeStore.initialize(context)
            val manual = CharacterLifeStore.state(id).getJSONObject("profile")
            assertEquals("自己的价值", manual.getString("values"))
            assertEquals("", manual.getString("traits"))
            assertEquals("自己的示例", manual.getString("dialogueExamples"))
            assertEquals("自己的核心人格", MigratedDomainStores.characters.get(id).persona)
            val stable = CharacterLifeStore.state(id).toString()
            CharacterLifeStore.initialize(context)
            assertEquals(stable, CharacterLifeStore.state(id).toString())
        } finally {
            save.invoke(CharacterLifeStore, id, original)
            MigratedDomainStores.characters.update(role)
        }
    }

    @Test fun v28SwitchUpdatesOnlyJiangDuStockCareAndPreservesManualCare() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val original = JSONObject(CharacterLifeStore.state(id).toString())
        val other = CharacterLifeStore.state("lulu").toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            for (care in listOf(CharacterProfileSchema.jiangDuV27Overrides.getValue("care"), "", "手写亲密偏好")) {
                val prior = JSONObject(original.toString()).put("jiangDuPresetVersion", 27)
                prior.getJSONObject("profile").put("care", care)
                save.invoke(CharacterLifeStore, id, prior)
                CharacterLifeStore.initialize(context)
                val actual = CharacterLifeStore.state(id).getJSONObject("profile").getString("care")
                if (care == CharacterProfileSchema.jiangDuV27Overrides.getValue("care")) {
                    assertEquals(CharacterProfileSchema.jiangDuV29Overrides.getValue("care"), actual)
                    assertTrue(CharacterRuntime.definition(id).persona.contains("switch"))
                    assertTrue(CharacterRuntime.definition(id).persona.contains("Dom"))
                    assertTrue(CharacterRuntime.definition(id).persona.contains("sub"))
                } else assertEquals(care, actual)
                assertEquals(prior.getJSONObject("relationshipAgreements").toString(),
                    CharacterLifeStore.state(id).getJSONObject("relationshipAgreements").toString())
                assertEquals(other, CharacterLifeStore.state("lulu").toString())
            }
        } finally {
            save.invoke(CharacterLifeStore, id, original)
        }
    }

    @Test fun v29CompactsV28AndReplacesOnlyStockSceneAndPersona() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val original = JSONObject(CharacterLifeStore.state(id).toString())
        val character = MigratedDomainStores.characters.get(id)
        val other = CharacterLifeStore.state("lulu").toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            for (custom in listOf(false, true)) {
                val prior = JSONObject(original.toString()).put("jiangDuPresetVersion", 28)
                val profile = prior.getJSONObject("profile")
                CharacterProfileSchema.jiangDuV28Overrides.forEach { (key, value) -> profile.put(key, value) }
                profile.put("dialogueExamples", CharacterProfileSchema.jiangDuDialogueExamplesV28)
                if (custom) profile.put("care", "").put("values", "手写价值").put("dialogueExamples", "手写示例")
                save.invoke(CharacterLifeStore, id, prior)
                MigratedDomainStores.characters.update(character.copy(persona =
                    if (custom) "手写人格" else CharacterProfileSchema.jiangDuV27Persona))
                CharacterLifeStore.initialize(context)
                val current = CharacterLifeStore.state(id).getJSONObject("profile")
                if (custom) {
                    assertEquals("", current.getString("care"))
                    assertEquals("手写价值", current.getString("values"))
                    assertEquals("手写示例", current.getString("dialogueExamples"))
                    assertEquals("手写人格", MigratedDomainStores.characters.get(id).persona)
                } else {
                    CharacterProfileSchema.jiangDuV29Overrides.forEach { (key, value) ->
                        assertEquals(key, value, current.getString(key))
                        assertFalse("Authoring uses third person: $key", value.contains("你"))
                    }
                    assertEquals(CharacterProfileSchema.jiangDuV29Persona,
                        MigratedDomainStores.characters.get(id).persona)
                    assertEquals(CharacterProfileSchema.jiangDuDialogueExamples, current.getString("dialogueExamples"))
                    val speech = CharacterSpeechIdentity.promptSection(id)
                    assertTrue(speech.contains("现在想让你疼我"))
                    assertFalse(speech.contains("选画"))
                }
                assertEquals(prior.getJSONObject("relationshipAgreements").toString(),
                    CharacterLifeStore.state(id).getJSONObject("relationshipAgreements").toString())
                assertEquals(other, CharacterLifeStore.state("lulu").toString())
            }
        } finally {
            save.invoke(CharacterLifeStore, id, original)
            MigratedDomainStores.characters.update(character)
        }
    }


    @Test fun ownerApprovedReplacementOverridesExistingEditsAndBlanksOnceForTheBoundCharacter() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val before = JSONObject(CharacterLifeStore.state(id).toString())
        val character = MigratedDomainStores.characters.get(id)
        val identity = CharacterIdentityStore.identities.value[id]
        val other = CharacterLifeStore.state("lulu").toString()
        val copy = MigratedDomainStores.characters.create("江渡")
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            // Even a v29 installation can still have all older owner-authored fields.
            val prior = JSONObject(before.toString()).put("jiangDuPresetVersion", 29)
            prior.remove("jiangDuApprovedReplacement20261104")
            prior.remove("jiangDuApprovedReplacementRevision")
            prior.remove("jiangDuApprovedReplacementBackup20261104")
            val oldProfile = prior.getJSONObject("profile")
            CharacterProfileSchema.jiangDuV29Overrides.keys.forEach {
                oldProfile.put(it, "以前手写的" + it)
            }
            oldProfile.put("cognitiveStyle", "").put("care", "")
                .put("dialogueExamples", "以前手写的示例")
            prior.put("intention", JSONObject().put("aim", "仍在推进的真实安排").put("stage", "considering"))
            val oldProfileText = oldProfile.toString()
            save.invoke(CharacterLifeStore, id, prior)
            MigratedDomainStores.characters.update(character.copy(persona = "以前手写的核心简介"))
            val unrelatedCopy = JSONObject().put("jiangDuPresetVersion", 29)
                .put("profile", JSONObject().put("values", "同名人物自己的价值"))
            save.invoke(CharacterLifeStore, copy.characterId, unrelatedCopy)

            CharacterLifeStore.initialize(context)
            val replaced = CharacterLifeStore.state(id)
            val profile = replaced.getJSONObject("profile")
            CharacterProfileSchema.jiangDuApprovedOverrides.forEach { (key, value) ->
                assertEquals("Approved complete replacement: " + key, value, profile.getString(key))
            }
            assertEquals(CharacterProfileSchema.jiangDuDialogueExamples, profile.getString("dialogueExamples"))
            assertEquals(CharacterProfileSchema.jiangDuV29Persona,
                MigratedDomainStores.characters.get(id).persona)
            assertTrue(replaced.getBoolean("jiangDuApprovedReplacement20261104"))
            val backup = replaced.getJSONObject("jiangDuApprovedReplacementBackup20261104")
            assertEquals("以前手写的核心简介", backup.getString("persona"))
            assertEquals(oldProfileText, backup.getJSONObject("profile").toString())
            assertEquals(identity, CharacterIdentityStore.identities.value[id])
            listOf("relationshipAgreements", "socialNames", "intention", "expressionPreferences").forEach {
                assertEquals(it, prior.opt(it)?.toString(), replaced.opt(it)?.toString())
            }
            assertEquals(other, CharacterLifeStore.state("lulu").toString())
            assertEquals(unrelatedCopy.toString(), CharacterLifeStore.state(copy.characterId).toString())
            assertEquals(copy, MigratedDomainStores.characters.get(copy.characterId))
            assertTrue(CharacterRuntime.definition(id).persona.contains(
                CharacterProfileSchema.jiangDuV29Overrides.getValue("cognitiveStyle")))
            assertTrue(CharacterSpeechIdentity.promptSection(id).contains(
                CharacterProfileSchema.jiangDuDialogueExamples))

            // This explicit replacement does not revoke editing after the approved upgrade.
            CharacterLifeStore.setProfile(id, "care", "")
            CharacterLifeStore.setProfile(id, "dialogueExamples", "应用新版之后的新示例")
            MigratedDomainStores.characters.update(
                MigratedDomainStores.characters.get(id).copy(persona = "应用新版之后的新简介"))
            val stable = CharacterLifeStore.state(id).toString()
            CharacterLifeStore.initialize(context)
            assertEquals(stable, CharacterLifeStore.state(id).toString())
            assertEquals("应用新版之后的新简介", MigratedDomainStores.characters.get(id).persona)
        } finally {
            save.invoke(CharacterLifeStore, id, before)
            MigratedDomainStores.characters.update(character)
            MigratedDomainStores.characters.delete(copy.characterId)
        }
    }

    @Test fun interruptedApprovedReplacementRetriesWithoutLosingTheOriginalRecoveryCopy() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val before = JSONObject(CharacterLifeStore.state(id).toString())
        val character = MigratedDomainStores.characters.get(id)
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val recovery = JSONObject().put("persona", "中断前原来的完整简介")
                .put("profile", JSONObject().put("care", "中断前原来的亲密设定"))
                .put("previousPresetVersion", 29)
            val partial = JSONObject(before.toString())
                .put("jiangDuApprovedReplacementBackup20261104", recovery)
            partial.remove("jiangDuApprovedReplacement20261104")
            partial.remove("jiangDuApprovedReplacementRevision")
            partial.getJSONObject("profile").put("care", "尚未完成的混合版本")
            save.invoke(CharacterLifeStore, id, partial)
            MigratedDomainStores.characters.update(character.copy(
                persona = CharacterProfileSchema.jiangDuV29Persona))
            CharacterLifeStore.initialize(context)
            val restored = CharacterLifeStore.state(id)
            assertEquals(recovery.toString(), restored
                .getJSONObject("jiangDuApprovedReplacementBackup20261104").toString())
            assertEquals(CharacterProfileSchema.jiangDuApprovedOverrides.getValue("care"),
                restored.getJSONObject("profile").getString("care"))
            assertEquals(CharacterProfileSchema.jiangDuDialogueExamples,
                restored.getJSONObject("profile").getString("dialogueExamples"))
            val stable = restored.toString()
            CharacterLifeStore.initialize(context)
            assertEquals(stable, CharacterLifeStore.state(id).toString())
        } finally {
            save.invoke(CharacterLifeStore, id, before)
            MigratedDomainStores.characters.update(character)
        }
    }

    @Test fun jiangDuSpeechRefreshUpgradesTheOldDefaultAndKeepsEditedOrBlankSpeech() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val before = CharacterLifeStore.state(id)
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        val peerBefore = CharacterLifeStore.state("lulu").toString()
        try {
            val stock = CharacterProfileSchema.jiangDuV29Overrides.getValue("speechHabits")
            val current = CharacterProfileSchema.jiangDuCurrentSpeechHabits
            for ((stored, expected) in listOf(stock to current, "我自己的口吻" to "我自己的口吻", "" to "")) {
                CharacterLifeStore.setProfile(id, "speechHabits", stored)
                CharacterLifeStore.syncBundledExpressionPresets()
                assertEquals(expected, CharacterLifeStore.state(id)
                    .getJSONObject("profile").getString("speechHabits"))
            }
            assertEquals(peerBefore, CharacterLifeStore.state("lulu").toString())
        } finally {
            save.invoke(CharacterLifeStore, id, before)
        }
    }

    @Test fun approvedValuesFollowUpUpdatesStockOnlyAndPreservesOwnerEditsAndIntimacy() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val original = JSONObject(CharacterLifeStore.state(id).toString())
        val character = MigratedDomainStores.characters.get(id)
        val peerBefore = CharacterLifeStore.state("lulu").toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val prior = JSONObject(original.toString())
                .put("jiangDuApprovedReplacement20261104", true)
                .put("jiangDuApprovedReplacementRevision", 2)
            val profile = prior.getJSONObject("profile")
            listOf("values", "motives", "perception", "conflict", "respect").forEach { key ->
                profile.put(key, CharacterProfileSchema.jiangDuV29Overrides.getValue(key))
            }
            profile.put("perception", "用户后来自己写的理解方式")
                .put("conflict", "")
                .put("care", "用户后来自己写的亲密偏好")
                .put("dialogueExamples", "用户另外写的对话样例")
            save.invoke(CharacterLifeStore, id, prior)
            CharacterLifeStore.initialize(context)

            val current = CharacterLifeStore.state(id)
            val actual = current.getJSONObject("profile")
            assertEquals(3, current.getInt("jiangDuApprovedReplacementRevision"))
            for (key in listOf("values", "motives", "respect")) {
                assertEquals(key, CharacterProfileSchema.jiangDuApprovedOverrides.getValue(key),
                    actual.getString(key))
            }
            assertEquals("用户后来自己写的理解方式", actual.getString("perception"))
            assertEquals("", actual.getString("conflict"))
            assertEquals("用户后来自己写的亲密偏好", actual.getString("care"))
            assertEquals("用户另外写的对话样例", actual.getString("dialogueExamples"))
            val role = CharacterRuntime.definition(id).personaPromptSection()
            assertTrue(role.contains("美与责任"))
            assertTrue(role.contains("当前本人一致性核对"))
            assertTrue(com.jiacimu.lulu.study.PersonalMiniTheater.capture(id)
                .material.contains(CharacterProfileSchema.jiangDuApprovedOverrides.getValue("values")))
            assertEquals(peerBefore, CharacterLifeStore.state("lulu").toString())

            // After this single upgrade, subsequent user edits always win.
            CharacterLifeStore.setProfile(id, "values", "我新写的价值观")
            CharacterLifeStore.initialize(context)
            assertEquals("我新写的价值观", CharacterLifeStore.state(id)
                .getJSONObject("profile").getString("values"))
        } finally {
            save.invoke(CharacterLifeStore, id, original)
            MigratedDomainStores.characters.update(character)
        }
    }

    @Test fun approvedSwitchRefinementReplacesOnlyIntimacyAndSampleOnce() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val id = CharacterLifeStore.BUNDLED_JIANG_DU_ID
        val before = JSONObject(CharacterLifeStore.state(id).toString())
        val character = MigratedDomainStores.characters.get(id)
        val other = CharacterLifeStore.state("lulu").toString()
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            val old = JSONObject(before.toString()).put("jiangDuApprovedReplacement20261104", true)
            old.remove("jiangDuApprovedReplacementRevision") // Already installed revision 1.
            old.getJSONObject("profile").put("values", "新版之后手写的价值")
                .put("care", "").put("dialogueExamples", CharacterProfileSchema.jiangDuDialogueExamplesV29)
            save.invoke(CharacterLifeStore, id, old)
            MigratedDomainStores.characters.update(character.copy(persona = "新版之后手写的简介"))
            CharacterLifeStore.initialize(context)
            val current = CharacterLifeStore.state(id)
            assertEquals(3, current.getInt("jiangDuApprovedReplacementRevision"))
            assertEquals(CharacterProfileSchema.jiangDuApprovedOverrides.getValue("care"),
                current.getJSONObject("profile").getString("care"))
            assertEquals(CharacterProfileSchema.jiangDuDialogueExamples,
                current.getJSONObject("profile").getString("dialogueExamples"))
            assertEquals("新版之后手写的价值", current.getJSONObject("profile").getString("values"))
            assertEquals("新版之后手写的简介", MigratedDomainStores.characters.get(id).persona)
            assertEquals(other, CharacterLifeStore.state("lulu").toString())
            assertTrue(com.jiacimu.lulu.study.PersonalMiniTheater.capture(id).material.contains(
                CharacterProfileSchema.jiangDuApprovedOverrides.getValue("care")))
            CharacterLifeStore.setProfile(id, "care", "这次更新以后继续编辑")
            CharacterLifeStore.initialize(context)
            assertEquals("这次更新以后继续编辑",
                CharacterLifeStore.state(id).getJSONObject("profile").getString("care"))
        } finally {
            save.invoke(CharacterLifeStore, id, before)
            MigratedDomainStores.characters.update(character)
        }
    }
}
