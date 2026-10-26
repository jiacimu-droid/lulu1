package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ConversationGroundingEngineTest {
    @Test fun onlyTheModelsExplicitGroundingDecisionMayChangeSharedUnderstanding() {
        val confirmed = DialogueMoveEngine.resolve(
            JSONObject().put("type", "acknowledge")
                .put("commonGroundDecision", "accept"),
            "我明白了", "我不是这个意思")
        assertEquals("accept", confirmed.groundingDecision)
        val rejected = DialogueMoveEngine.resolve(
            JSONObject().put("type", "other_initiated_repair")
                .put("commonGroundDecision", "reject"),
            "原来我理解错了", "对，就是这样")
        assertEquals("reject", rejected.groundingDecision)
    }

    @Test fun lexicalAgreementOrNegationDoesNotBecomeGroundingFact() {
        for (input in listOf("对，就是这个意思", "嗯对", "你终于懂了！",
                "对了，我还有件事", "我不是这个意思")) {
            assertEquals("", DialogueMoveEngine.resolve(
                JSONObject().put("type", "answer"), "", input).groundingDecision)
        }
        assertEquals("", DialogueMoveEngine.resolve(
            JSONObject().put("type", "answer")
                .put("commonGroundDecision", "auto"), "", "你错了").groundingDecision)
    }
}
