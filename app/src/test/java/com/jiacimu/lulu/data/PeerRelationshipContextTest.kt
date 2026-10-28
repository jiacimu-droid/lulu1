package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PeerRelationshipContextTest {
    @Test fun realBestFriendAgreementKeepsPeerAndNonServantBoundaries() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val prompt = PeerRelationshipContext.promptSection("lulu")
        assertTrue(prompt.contains("平等朋友"))
        assertTrue(prompt.contains("不代表她在要求哄睡"))
        assertTrue(prompt.contains("过去请别的角色做过某事"))
        assertTrue(prompt.contains("根据自己意愿"))
        assertTrue(prompt.contains("不能因闺蜜身份自动拒绝").not()) // No forced refusal.
        assertTrue(prompt.contains("不要因闺蜜身份自动拒绝"))
        assertEquals("", PeerRelationshipContext.promptSection(
            CharacterLifeStore.BUNDLED_JIANG_DU_ID))
    }

    @Test fun changingRelationshipImmediatelyStopsPeerOverlayWithoutResettingHistory() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        val previous = JSONObject(CharacterLifeStore.state("lulu").toString())
        val save = CharacterLifeStore.javaClass.getDeclaredMethod(
            "save", String::class.java, JSONObject::class.java).apply { isAccessible = true }
        try {
            assertTrue(CharacterLifeStore.setRelationshipAgreement(
                "lulu", "user", "普通朋友", "只是熟人，没有约定必须亲近"))
            assertEquals("", PeerRelationshipContext.promptSection("lulu"))
            assertTrue(CharacterLifeStore.setRelationshipAgreement(
                "lulu", "user", "最好的闺蜜", "平等的闺蜜，可以独立拒绝某种邀约"))
            assertTrue(PeerRelationshipContext.promptSection("lulu")
                .contains("可以独立拒绝某种邀约"))
        } finally {
            save.invoke(CharacterLifeStore, "lulu", previous)
        }
    }
}
