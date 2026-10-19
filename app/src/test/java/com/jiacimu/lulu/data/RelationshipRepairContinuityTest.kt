package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONArray
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
class RelationshipRepairContinuityTest {
    @Test fun onlyActualAffirmativeUserClosureEndsRepair() {
        assertTrue(RelationshipRepairContinuity.userExplicitlySettled("我们已经把这件事情说开了。"))
        assertTrue(RelationshipRepairContinuity.userExplicitlySettled("我原谅你了，这件事过去了。"))
        assertTrue(RelationshipRepairContinuity.userExplicitlySettled("好啦，我不生气了。"))
        assertFalse(RelationshipRepairContinuity.userExplicitlySettled("我还没有原谅你。"))
        assertFalse(RelationshipRepairContinuity.userExplicitlySettled("道歉不等于原谅。"))
        assertFalse(RelationshipRepairContinuity.userExplicitlySettled("如果我们和好了呢？"))
        assertFalse(RelationshipRepairContinuity.userExplicitlySettled("他说已经和好了"))
        assertFalse(RelationshipRepairContinuity.userExplicitlySettled("好吧，我再考虑考虑。"))
    }

    @Test fun anApologyIsNotEvidenceAndIndependentGoalsStayOpen() {
        assertFalse(RelationshipRepairContinuity.userExplicitlySettled("对不起，是我不好"))
        assertTrue(RelationshipRepairContinuity.isRepairGoal("认真修复刚才伤到的关系"))
        assertTrue(RelationshipRepairContinuity.isRepairConcern("怎样弥补让她失望的伤害"))
        assertFalse(RelationshipRepairContinuity.isRepairGoal("继续独自读完小说"))
        assertFalse(RelationshipRepairContinuity.isRepairConcern("和群友道歉"))
        assertTrue(RelationshipRepairContinuity.isReopenedApology("对不起，宝宝，是我不好"))
        assertFalse(RelationshipRepairContinuity.isReopenedApology("来看看我刚发现的书吧"))
    }

    @Test fun aSettlementTurnCannotReopenAnAlreadyClosedRepairConcern() {
        val proposal = JSONObject().put("pendingConcerns", JSONArray()
            .put(JSONObject().put("status", "open").put("focus", "怎么再次向她道歉")
                .put("whyItMatters", "此前争执"))
            .put(JSONObject().put("status", "open").put("focus", "想继续读自己的书")
                .put("whyItMatters", "个人兴趣")))
        val filtered = RelationshipRepairContinuity.reconcileConcernProposals(
            proposal, "我们已经说开了")
        val remaining = filtered!!.getJSONArray("pendingConcerns")
        assertEquals(1, remaining.length())
        assertEquals("想继续读自己的书", remaining.getJSONObject(0).getString("focus"))
        assertEquals(2, RelationshipRepairContinuity.reconcileConcernProposals(
            proposal, "我还没原谅你")!!.getJSONArray("pendingConcerns").length())
    }

    @Test fun historicalUserWordsMustNotOverwriteNewerGoalChanges() {
        val at = Instant.parse("2026-10-19T08:00:00Z")
        assertTrue(RelationshipRepairContinuity.eligiblePriorState(
            "2026-10-19T07:30:00Z", "2026-10-19T07:45:00Z", at))
        assertFalse(RelationshipRepairContinuity.eligiblePriorState(
            "2026-10-19T07:30:00Z", "2026-10-19T08:30:00Z", at))
        assertFalse(RelationshipRepairContinuity.eligiblePriorState(
            "2026-10-19T09:00:00Z", "", at))
    }

    @Test fun explicitSettlementClosesOnlyRelatedPendingStateAndPreservesHistory() {
        val app = RuntimeEnvironment.getApplication() as Context
        CharacterInnerLifeStore.initialize(app)
        CharacterLifeStore.initialize(app)
        val role = "settlement-integration-role"
        CharacterInnerLifeStore.clear(role)
        CharacterLifeStore.clearHistory(role)
        val at = Instant.now().minusSeconds(180)
        CharacterLifeStore.consider(role, JSONObject()
            .put("aim", "认真修复刚才伤到的关系")
            .put("motive", "我想把误会解释清楚")
            .put("nextStep", "先道歉，再根据她的反馈判断"), at)
        val concerns = JSONArray()
            .put(JSONObject().put("status", "open")
                .put("focus", "如何向她道歉并取得原谅")
                .put("whyItMatters", "刚才说话伤到了她"))
            .put(JSONObject().put("status", "open")
                .put("focus", "我为什么喜欢阅读这本书")
                .put("whyItMatters", "想了解自己的兴趣"))
        CharacterInnerLifeStore.recordCausalTransition(
            characterId = role, evidenceId = "earlier-dispute",
            appraisal = JSONObject().put("pendingConcerns", concerns),
            innerLife = null, innerThoughtBasis = null,
            selectedAction = "silent", innerThought = "", reason = "",
            now = at,
        )
        assertEquals(2, CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("openConcerns").length())
        CharacterLifeStore.observeUserGoalEvidence(
            role, "still-thinking", "我还得考虑一下", at.plusSeconds(60))
        assertNotNull(CharacterLifeStore.state(role).optJSONObject("intention"))
        CharacterLifeStore.observeUserGoalEvidence(
            role, "explicit-reconciliation", "我们已经说开了，我不生气了",
            at.plusSeconds(120))
        assertNull(CharacterLifeStore.state(role).optJSONObject("intention"))
        val prior = CharacterLifeStore.state(role).getJSONObject("previousIntention")
        assertEquals("completed", prior.getString("stage"))
        assertEquals("explicit-reconciliation", prior.getString("completionEvidenceId"))
        val mind = CharacterInnerLifeStore.snapshot(role)
        assertEquals(1, mind.getJSONArray("openConcerns").length())
        assertEquals("我为什么喜欢阅读这本书", mind.getJSONArray("openConcerns")
            .getJSONObject(0).getString("focus"))
        assertEquals("explicit-reconciliation", mind.getJSONArray("closedConcerns")
            .getJSONObject(0).getString("closureEvidenceId"))
        // The same delivered evidence must not create duplicate closures.
        CharacterLifeStore.observeUserGoalEvidence(
            role, "explicit-reconciliation", "我们已经说开了，我不生气了",
            at.plusSeconds(120))
        assertEquals(1, CharacterInnerLifeStore.snapshot(role)
            .getJSONArray("closedConcerns").length())
        CharacterLifeStore.clearHistory(role)
        CharacterInnerLifeStore.clear(role)
    }
}
