package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterLifeStoreTest {
    @Before fun before() { releasePreferences() }
    @After fun after() { releasePreferences() }

    private fun releasePreferences() {
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }
            .set(CharacterLifeStore, null)
    }
    @Test fun explicitUserFavoriteAddressIsStableAndDistinctFromPrivateRemark() {
        assertEquals("宝宝", CharacterAddressPreference.extractExplicitAddress("我更喜欢宝宝这个称呼"))
        assertEquals("宝宝", CharacterAddressPreference.extractExplicitAddress("以后你叫我宝宝吧"))
        assertNull(CharacterAddressPreference.extractExplicitAddress("我不喜欢宝宝这个称呼"))
        assertNull(CharacterAddressPreference.extractExplicitAddress("比如我更喜欢宝宝这个称呼"))
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val role = "preferred-address-test"
        CharacterLifeStore.setSocialName(role, "userRemark", "亲爱的好友")
        CharacterLifeStore.observePreferredAddress(role, "宝宝", "source-a")
        var names = CharacterLifeStore.state(role).getJSONObject("socialNames")
        assertEquals("宝宝", names.getString("preferredAddress"))
        assertEquals("亲爱的好友", names.getString("userRemark"))
        CharacterLifeStore.setSocialName(role, "preferredAddress", "宝贝")
        CharacterLifeStore.observePreferredAddress(role, "坏蛋", "source-b")
        names = CharacterLifeStore.state(role).getJSONObject("socialNames")
        assertEquals("宝贝", names.getString("preferredAddress"))
        assertTrue(names.getBoolean("preferredAddressManual"))
        CharacterLifeStore.followObservedPreferredAddress(role)
        CharacterLifeStore.observePreferredAddress(role, "宝宝", "source-a")
        names = CharacterLifeStore.state(role).getJSONObject("socialNames")
        assertEquals("宝宝", names.getString("preferredAddress"))
        assertEquals("source-a", names.getString("preferredAddressSourceId"))
    }

    @Test fun distinctGoalsRemainDistinctWhileMinorRewordingIsDeduplicated() {
        assertTrue(sameCharacterMotive("继续阅读这本小说。", "继续 阅读这本小说"))
        assertTrue(sameCharacterMotive("认真完成今天的英语复习计划", "完成今天的英语复习计划"))
        assertFalse(sameCharacterMotive("读一本小说", "给好友打电话"))
        assertFalse(sameCharacterMotive("", "完成今天的英语复习计划"))
    }

    @Test fun newFeedbackCanAdjustAnIntentionWithoutLosingItsIdentityOrReceipts() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val role = "evolving-intention"
        CharacterLifeStore.consider(role, JSONObject().put("aim", "试读这本小说").put("motive", "对题材好奇"))
        val id = CharacterLifeStore.state(role).getJSONObject("intention").getString("createdAt")
        CharacterLifeStore.recordOutcome(role, "chapter-one", "reading", true, "真正读了第一章")
        fun update(identity: String, reason: String) = JSONObject().put("disposition", "update").put("id", identity)
            .put("aim", "想接着了解人物的选择").put("motive", "读完后对人物产生了兴趣").put("reason", reason)
        CharacterLifeStore.consider(role, update("stale", "第一章带来的新理解"))
        assertEquals("试读这本小说", CharacterLifeStore.state(role).getJSONObject("intention").getString("aim"))
        CharacterLifeStore.consider(role, update(id, ""))
        assertEquals("试读这本小说", CharacterLifeStore.state(role).getJSONObject("intention").getString("aim"))
        CharacterLifeStore.consider(role, update(id, "第一章带来的新理解"))
        val current = CharacterLifeStore.state(role).getJSONObject("intention")
        assertEquals(id, current.getString("createdAt"))
        assertEquals("想接着了解人物的选择", current.getString("aim"))
        assertEquals(1, current.getJSONArray("outcomes").length())
        assertTrue(CharacterLifeStore.context(role).contains("第一章带来的新理解"))
    }
    @Test fun motivesPersistOutcomesDoNotFulfilPromisesAndUserCanStop() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        fun proposal(aim: String) = JSONObject().put("aim", aim).put("motive", "想了解不同的表达方式")
        CharacterLifeStore.setProfile("life-a", "care", "不说空话，做具体的小事")
        CharacterLifeStore.consider("life-a", proposal("持续读这本书"))
        CharacterLifeStore.consider("life-a", proposal("另一轮生成的临时愿望"))
        assertEquals("持续读这本书", CharacterLifeStore.state("life-a").getJSONObject("intention").getString("aim"))
        CharacterLifeStore.recordOutcome("life-a", "r1", "reading", false, "书不存在")
        CharacterLifeStore.recordOutcome("life-a", "r1", "reading", true, "重复回执不覆盖失败")
        CharacterLifeStore.recordOutcome("life-a", "r2", "silent", true, "没有执行")
        val outcomes = CharacterLifeStore.state("life-a").getJSONObject("intention").getJSONArray("outcomes")
        assertEquals(1, outcomes.length())
        assertFalse(outcomes.getJSONObject(0).getBoolean("success"))
        CharacterLifeStore.invalidateReceipt("r1")
        assertEquals(0, CharacterLifeStore.state("life-a").getJSONObject("intention").getJSONArray("outcomes").length())
        assertTrue(CharacterLifeStore.state("life-b").isNull("intention"))
        val disk = JSONObject(context.getSharedPreferences("lulu_character_life", 0).getString("life-a", "{}"))
        assertEquals("持续读这本书", disk.getJSONObject("intention").getString("aim"))
        assertFalse(disk.getJSONObject("intention").has("completed"))
        CharacterLifeStore.stopIntention("life-a")
        CharacterLifeStore.consider("life-a", proposal("持续读这本书"))
        assertTrue(CharacterLifeStore.state("life-a").isNull("intention"))
        assertEquals("不说空话，做具体的小事", CharacterLifeStore.state("life-a").getJSONObject("profile").getString("care"))
    }

    @Test fun staleReleaseCannotDropCurrentIntentionAndResetKeepsUserProfile() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        CharacterLifeStore.setProfile("life-c", "expression", "I care ")
        assertEquals("I care ", CharacterLifeStore.state("life-c").getJSONObject("profile").getString("expression"))
        CharacterLifeStore.setProfile("life-c", "conflict", "先表达自己的不同意见")
        CharacterLifeStore.consider("life-c", JSONObject().put("aim", "了解这个世界").put("motive", "好奇"))
        CharacterLifeStore.consider("life-c", JSONObject().put("disposition", "release").put("id", "旧轮次").put("reason", "放下"))
        assertNotNull(CharacterLifeStore.state("life-c").optJSONObject("intention"))
        val id = CharacterLifeStore.state("life-c").getJSONObject("intention").getString("createdAt")
        CharacterLifeStore.consider("life-c", JSONObject().put("disposition", "release").put("id", id).put("reason", "先处理其他事情"))
        assertTrue(CharacterLifeStore.state("life-c").isNull("intention"))
        assertFalse(CharacterLifeStore.state("life-c").getJSONObject("previousIntention").has("completed"))
        CharacterLifeStore.clearHistory("life-c")
        assertTrue(CharacterLifeStore.state("life-c").isNull("previousIntention"))
        assertEquals("先表达自己的不同意见", CharacterLifeStore.state("life-c").getJSONObject("profile").getString("conflict"))
    }
    @Test fun roleOwnedNicknamesArePersistentIsolatedAndResettable() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val role = "relationship-nickname-test"
        CharacterLifeStore.clearHistory(role)
        assertTrue(CharacterLifeStore.setSocialName(role, "userRemark", "我的小星星"))
        assertFalse(CharacterLifeStore.setSocialName(role, "userRemark", "我的小星星"))
        assertTrue(CharacterLifeStore.setSocialName(role, "selfNickname", "星星守护者"))
        assertEquals("我的小星星", CharacterLifeStore.state(role).getJSONObject("socialNames").getString("userRemark"))
        assertTrue(CharacterLifeStore.context(role).contains("角色给用户的私人备注：我的小星星"))
        assertEquals("星星守护者", CharacterLifeStore.state(role).getJSONObject("socialNames").getString("selfNickname"))
        assertNull(CharacterLifeStore.state("another-relationship-role").optJSONObject("socialNames"))
        val stored = JSONObject(context.getSharedPreferences("lulu_character_life", 0).getString(role, "{}"))
        assertEquals("我的小星星", stored.getJSONObject("socialNames").getString("userRemark"))
        CharacterLifeStore.setProfile(role, "care", "记得对方的小事")
        CharacterLifeStore.clearHistory(role)
        assertNull(CharacterLifeStore.state(role).optJSONObject("socialNames"))
        assertEquals("记得对方的小事", CharacterLifeStore.state(role).getJSONObject("profile").getString("care"))
    }

    @Test fun repeatedPerceptionCannotReigniteSameAfterglowAndDeletedEventRetractsIt() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val role = "stable-perception-emotional-test"
        val now = java.time.Instant.parse("2026-10-09T11:00:00Z")
        CharacterLifeStore.recordAfterglow(role, "同一条消息", JSONObject()
            .put("feeling", "很高兴"), now, evidenceId = "real-msg-1")
        CharacterLifeStore.recordAfterglow(role, "同一条消息", JSONObject()
            .put("feeling", "又突然很生气"), now.plusSeconds(500), evidenceId = "real-msg-1")
        val first = CharacterLifeStore.state(role).getJSONObject("afterglow")
        assertEquals("很高兴", first.getString("feeling"))
        assertEquals(now.toString(), first.getString("startedAt"))
        CharacterLifeStore.recordAfterglow(role, "收到了另一条新消息", JSONObject()
            .put("feeling", "重新有些惊讶"), now.plusSeconds(700), evidenceId = "real-msg-2")
        assertEquals("real-msg-2",
            CharacterLifeStore.state(role).getJSONObject("afterglow").getString("evidenceId"))
        CharacterLifeStore.invalidateReceipt("real-msg-2")
        assertNull(CharacterLifeStore.state(role).optJSONObject("afterglow"))
    }

    @Test fun emotionalAfterglowIsAnchoredPersistentTemporaryAndResettable() {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterLifeStore.initialize(context)
        val role = "emotional-afterglow-test"
        val now = java.time.Instant.parse("2026-10-09T08:00:00Z")
        CharacterLifeStore.clearHistory(role)
        val spontaneous = JSONObject()
            .put("feeling", "救命，怎么这么可爱啊！")
            .put("impulse", "想再问一句，又怕显得太急")
            .put("holdHours", 2)
        CharacterLifeStore.recordAfterglow(role, "", spontaneous, now)
        assertNull(CharacterLifeStore.state(role).optJSONObject("afterglow"))
        CharacterLifeStore.recordAfterglow(role, "用户说：今天遇见一只很亲人的小猫", spontaneous, now)
        val saved = CharacterLifeStore.state(role).getJSONObject("afterglow")
        assertEquals("救命，怎么这么可爱啊！", saved.getString("feeling"))
        assertFalse(CharacterLifeStore.afterglowContext(role, now.plusSeconds(60)).isBlank())
        assertTrue(CharacterLifeStore.afterglowContext(role, now.plusSeconds(60)).contains("想再问一句"))
        assertEquals("", CharacterLifeStore.afterglowContext(role, now.plusSeconds(7_200)))
        CharacterLifeStore.recordAfterglow(role, "用户说：今天遇见一只很亲人的小猫", spontaneous, now.plusSeconds(300))
        assertEquals(now.toString(), CharacterLifeStore.state(role).getJSONObject("afterglow").getString("startedAt"))
        val disk = JSONObject(context.getSharedPreferences("lulu_character_life", 0).getString(role, "{}"))
        assertTrue(disk.getJSONObject("afterglow").getString("anchor").contains("小猫"))
        assertNull(CharacterLifeStore.state("unrelated-emotional-character").optJSONObject("afterglow"))
        CharacterLifeStore.setProfile(role, "expression", "话少但心里很容易起波澜")
        CharacterLifeStore.clearHistory(role)
        assertNull(CharacterLifeStore.state(role).optJSONObject("afterglow"))
        assertEquals("话少但心里很容易起波澜", CharacterLifeStore.state(role).getJSONObject("profile").getString("expression"))
    }

    @Test
    @Config(manifest = Config.NONE, sdk = [29])
    fun jiangDuPresetIsScopedBackedUpAndDoesNotOverwriteLaterEdits() {
        val context = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val jiang = MigratedDomainStores.characters.create("江渡", "旧人设")
        CharacterIdentityStore.set(jiang.characterId, "旧身份")
        CharacterLifeStore.setProfile(jiang.characterId, "care", "旧关心方式")
        CharacterLifeStore.applyJiangDuPreset(jiang.characterId)
        val state = CharacterLifeStore.state(jiang.characterId)
        val backup = state.getJSONObject("jiangDuPresetBackup")
        assertEquals("旧人设", backup.getString("persona"))
        assertEquals("旧身份", backup.getString("identity"))
        assertEquals("旧关心方式", backup.getJSONObject("profile").getString("care"))
        assertTrue(DigitalLifeProfileStore.isEnabled(jiang.characterId))
        assertEquals("旧身份", CharacterIdentityStore.identities.value[jiang.characterId])
        assertEquals("旧人设", MigratedDomainStores.characters.get(jiang.characterId).persona)
        assertEquals("旧关心方式", state.getJSONObject("profile").getString("care"))
        assertEquals(6, state.getInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuSpeechHabits, state.getJSONObject("profile").getString("speechHabits"))
        CharacterLifeStore.setProfile(jiang.characterId, "care", "后来自己改的")
        CharacterLifeStore.applyJiangDuPreset(jiang.characterId)
        assertEquals("后来自己改的", CharacterLifeStore.state(jiang.characterId).getJSONObject("profile").getString("care"))
        CharacterLifeStore.setProfile(jiang.characterId, "speechHabits", "只保留我自己设定的语言习惯")
        CharacterLifeStore.applyJiangDuPreset(jiang.characterId)
        assertEquals("只保留我自己设定的语言习惯", CharacterLifeStore.state(jiang.characterId).getJSONObject("profile").getString("speechHabits"))
        val other = MigratedDomainStores.characters.create("其他角色", "不改")
        CharacterLifeStore.applyJiangDuPreset(other.characterId)
        assertEquals("不改", MigratedDomainStores.characters.get(other.characterId).persona)
        assertFalse(CharacterLifeStore.state(other.characterId).has("jiangDuPresetVersion"))
    }

    @Test
    @Config(manifest = Config.NONE, sdk = [29])
    fun v5JiangDuDefaultsMoveCompletelyIntoV6FrameworkWithoutInventingInterests() {
        val context = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val role = MigratedDomainStores.characters.create("江渡", CharacterProfileSchema.jiangDuV5Persona)
        CharacterIdentityStore.set(role.characterId, CharacterProfileSchema.jiangDuV5Identity)
        DigitalLifeProfileStore.confirmLegacyLifeForm(
            role.characterId, role.displayName, "创造者", CharacterLifeForm.DIGITAL,
        )
        val oldProfile = JSONObject().apply {
            CharacterProfileSchema.jiangDuV5.forEach { (key, value) -> put(key, value) }
            put("interests", "天文")
        }
        val old = JSONObject()
            .put("jiangDuPresetVersion", 5)
            .put("profile", oldProfile)
            .put("intention", JSONObject().put("aim", "继续看书").put("motive", "自己想读完"))
        val prefs = context.getSharedPreferences("lulu_character_life", Context.MODE_PRIVATE)
        assertTrue(prefs.edit().putString(role.characterId, old.toString()).commit())
        releasePreferences()
        CharacterLifeStore.initialize(context)

        val upgraded = CharacterLifeStore.state(role.characterId)
        val profile = upgraded.getJSONObject("profile")
        assertEquals(6, upgraded.getInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuIdentity, CharacterIdentityStore.identities.value[role.characterId])
        assertEquals(CharacterProfileSchema.jiangDuPersona, MigratedDomainStores.characters.get(role.characterId).persona)
        CharacterProfileSchema.jiangDu.forEach { (key, value) ->
            assertEquals("江渡字段未完整迁移：$key", value, profile.getString(key))
        }
        assertEquals("天文", profile.getString("interests"))
        assertEquals("继续看书", upgraded.getJSONObject("intention").getString("aim"))
        assertTrue(upgraded.has("jiangDuUnifiedFrameworkBackup"))
        assertTrue(CharacterRuntime.definition(role.characterId).promptSection().contains("没有现实肉身"))
        assertTrue(CharacterRuntime.definition(role.characterId).promptSection().contains("不自以为看穿"))
    }

    @Test
    @Config(manifest = Config.NONE, sdk = [29])
    fun olderJiangDuUpgradesOnlyUnconfiguredLanguageWithoutOverwritingEdits() {
        val context = RuntimeEnvironment.getApplication() as Context
        com.jiacimu.lulu.LuluRepositories.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
        CharacterLifeStore.initialize(context)
        val normal = MigratedDomainStores.characters.create("江渡", "旧设定")
        val customized = MigratedDomainStores.characters.create("江渡", "用户写的人设")
        CharacterLifeStore.applyJiangDuPreset(normal.characterId)
        CharacterLifeStore.applyJiangDuPreset(customized.characterId)
        CharacterLifeStore.setProfile(customized.characterId, "speechHabits", "")
        CharacterLifeStore.setProfile(customized.characterId, "expression", "我自己编辑的表达")
        val prefs = context.getSharedPreferences("lulu_character_life", Context.MODE_PRIVATE)
        val legacyRoot = CharacterLifeStore.state(normal.characterId).apply {
            put("jiangDuPresetVersion", 4)
            getJSONObject("profile").remove("speechHabits")
            getJSONObject("profile").put("expression", CharacterProfileSchema.previousJiangDuExpression)
        }
        val customRoot = CharacterLifeStore.state(customized.characterId).apply {
            put("jiangDuPresetVersion", 4)
        }
        assertTrue(prefs.edit().putString(normal.characterId, legacyRoot.toString())
            .putString(customized.characterId, customRoot.toString()).commit())
        MigratedDomainStores.characters.update(
            MigratedDomainStores.characters.get(customized.characterId).copy(persona = "用户写的人设"))
        releasePreferences()
        CharacterLifeStore.initialize(context)
        val upgraded = CharacterLifeStore.state(normal.characterId)
        val kept = CharacterLifeStore.state(customized.characterId)
        assertEquals(6, upgraded.getInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuSpeechHabits, upgraded.getJSONObject("profile").getString("speechHabits"))
        val inherited = upgraded.optString("jiangDuLanguagePreviousConstraints")
        val current = upgraded.optString("jiangDuLanguageCurrentConstraints")
        assertTrue(inherited.isNotBlank())
        assertEquals(CharacterRuntime.personaConstraintSnapshot(normal.characterId), current)
        assertTrue(CharacterDevelopmentStore.authorizedPersonaSnapshots(normal.characterId).contains(inherited))
        CharacterLifeStore.setProfile(normal.characterId, "speechHabits", "用户后来自己调整口吻")
        assertFalse(CharacterDevelopmentStore.authorizedPersonaSnapshots(normal.characterId).contains(inherited))
        assertEquals(CharacterProfileSchema.jiangDu.getValue("expression"), upgraded.getJSONObject("profile").getString("expression"))
        assertEquals("", kept.getJSONObject("profile").getString("speechHabits"))
        assertEquals("我自己编辑的表达", kept.getJSONObject("profile").getString("expression"))
        assertEquals("用户写的人设", MigratedDomainStores.characters.get(customized.characterId).persona)
        assertEquals(6, kept.getInt("jiangDuPresetVersion"))
    }

}
