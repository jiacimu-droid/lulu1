package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterRelationalInitiativeTest {
    private val id = "relationship-initiative-test"
    private val now = Instant.parse("2026-10-16T09:00:00Z")

    private fun start(): Context {
        val context = RuntimeEnvironment.getApplication() as Context
        CharacterRelationalInitiative.initialize(context)
        CharacterRelationalInitiative.clear(id)
        CharacterInnerLifeStore.clear(id)
        return context
    }

    private fun wish() = JSONObject().put("status", "ready")
        .put("aim", "用真实读过的故事给对方一个小惊喜")
        .put("why", "因为我确实觉得这个故事有趣")
        .put("approach", "下次自然聊到时分享")
        .put("deliveryAction", "message")

    @Test fun oldRelationalWishUsesCanonicalMotiveAndUnrelatedActDoesNotSettleIt() {
        start()
        CharacterRelationalInitiative.recordDecision(id, wish(), "silent", false, "还在考虑", now)
        assertEquals(1, CharacterInnerLifeStore.snapshot(id).getJSONArray("motives").length())
        assertTrue(CharacterRelationalInitiative.context(id, now).contains("目标="))
        assertTrue(CharacterRelationalInitiative.expressionHandoff(id).contains("自己此前考虑"))
        CharacterRelationalInitiative.recordDecision(id, wish(), "journal", true, "日记发出了", now)
        val goal = CharacterInnerLifeStore.snapshot(id).getJSONArray("motives").getJSONObject(0)
        assertEquals("active", goal.getString("status"))
        assertEquals(0, goal.getJSONArray("outcomes").length())
    }

    @Test fun unverifiedSentClaimCannotCompleteARealWish() {
        start()
        CharacterRelationalInitiative.recordDecision(id, wish(), "message", true,
            "模型声称完成但没有送达证据", now)
        val goal = CharacterInnerLifeStore.snapshot(id).getJSONArray("motives").getJSONObject(0)
        assertEquals("active", goal.getString("status"))
        assertEquals(0, goal.getJSONArray("outcomes").length())
    }

    @Test fun oldLegacyPreferenceIsImportedOnlyOnce() {
        val context = start()
        context.getSharedPreferences("lulu_relational_initiative_v1", 0).edit()
            .putString(id, JSONObject().put("pending", JSONObject()
                .put("status", "ready").put("aim", "多听她说完")
                .put("why", "觉得她需要时间")
                .put("approach", "耐心接话")
                .put("personaSignature", CharacterRuntime.personaConstraintSnapshot(id).hashCode().toString())
                .put("evidenceId", "")).toString()).commit()
        val first = CharacterRelationalInitiative.context(id, now)
        assertTrue(first.contains("多听她说完"))
        assertEquals(1, CharacterInnerLifeStore.snapshot(id).getJSONArray("motives").length())
        CharacterRelationalInitiative.context(id, now)
        assertEquals(1, CharacterInnerLifeStore.snapshot(id).getJSONArray("motives").length())
    }

    @Test fun independentRolePlansNeverLeakAcrossCharacters() {
        start()
        val other = "other-character-goal-test"
        CharacterInnerLifeStore.clear(other)
        CharacterRelationalInitiative.recordDecision(id, wish(), "silent", false, "", now)
        assertEquals(0, CharacterGoalConvergence.relationships(
            CharacterInnerLifeStore.snapshot(other).optJSONArray("motives")).size)
    }
}
