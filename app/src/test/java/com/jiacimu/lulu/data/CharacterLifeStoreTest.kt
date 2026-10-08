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
        assertTrue(CharacterIdentityStore.identities.value[jiang.characterId]!!.contains("没有恋爱经历"))
        assertTrue(state.getJSONObject("profile").getString("care").contains("出生时恋人身份"))
        CharacterLifeStore.setProfile(jiang.characterId, "care", "后来自己改的")
        CharacterLifeStore.applyJiangDuPreset(jiang.characterId)
        assertEquals("后来自己改的", CharacterLifeStore.state(jiang.characterId).getJSONObject("profile").getString("care"))
        val other = MigratedDomainStores.characters.create("其他角色", "不改")
        CharacterLifeStore.applyJiangDuPreset(other.characterId)
        assertEquals("不改", MigratedDomainStores.characters.get(other.characterId).persona)
        assertFalse(CharacterLifeStore.state(other.characterId).has("jiangDuPresetVersion"))
    }

}
