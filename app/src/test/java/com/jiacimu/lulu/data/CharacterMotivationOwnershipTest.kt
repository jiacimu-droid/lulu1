package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CharacterMotivationOwnershipTest {
    @Test fun newLegacyIntentionBecomesSingleCanonicalMotive() {
        val oldProposal = JSONObject().put("aim", "读完一本真正感兴趣的书")
            .put("motive", "想知道人物后来的选择")
            .put("nextStep", "阅读第二章")
        val result = CharacterMotivationOwnership.canonicalInnerLife(oldProposal, null)
        val motives = result!!.getJSONArray("motives")
        assertEquals(1, motives.length())
        assertEquals("start", motives.getJSONObject(0).getString("op"))
        assertEquals("读完一本真正感兴趣的书", motives.getJSONObject(0).getString("aim"))
        assertFalse(result.has("intention"))
    }

    @Test fun existingMotiveIsNotStartedTwice() {
        val oldProposal = JSONObject().put("aim", "继续阅读这本书")
            .put("motive", "很想知道结局")
        val innerLife = JSONObject().put("motives", JSONArray().put(
            JSONObject().put("op", "start").put("aim", "继续阅读这本书").put("why", "已有原因")))
        val result = CharacterMotivationOwnership.canonicalInnerLife(oldProposal, innerLife)
        assertEquals(1, result!!.getJSONArray("motives").length())
    }

    @Test fun legacyUpdatesDoNotCreateNewMotives() {
        val legacyUpdate = JSONObject().put("disposition", "pause").put("id", "legacy-id")
            .put("reason", "暂时不想继续")
        assertNull(CharacterMotivationOwnership.canonicalInnerLife(legacyUpdate, null))
    }

    @Test fun shortLivedOrientationDoesNotBecomePermanentGoal() {
        val legacySuggestion = JSONObject().put("aim", "了解她是什么样的人")
            .put("motive", "希望建立关系")
        val life = CharacterMotivationOwnership.canonicalInnerLife(legacySuggestion, null)
        // A vague aspiration without a concrete step is not an executable goal.
        // The stale-goal filter is the same one used by the existing motive store.
        assertNull(life)
    }

    @Test fun runtimePrinciplesKeepActionEvidenceAndRelationshipSeparation() {
        val policy = CharacterDecisionProtocol.runtimePrinciples
        assertTrue(policy.contains("innerLife.motives"))
        assertTrue(policy.contains("pendingConcerns"))
        assertTrue(policy.contains("学习"))
        assertTrue(policy.contains("收藏"))
        assertTrue(policy.contains("回执"))
        assertTrue(policy.length < 3000)
    }
}
