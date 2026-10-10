package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class DialogueMoveEngineTest {
    @Test fun aClearCorrectionShouldNotForceAnotherQuestion() {
        val feedback = "不是这个意思，我希望你能主动想个办法逗我开心，而不是又问我要做什么"
        assertTrue(DialogueMoveEngine.userInitiatesRepair(feedback))
        assertTrue(DialogueMoveEngine.correctionSuppliesDirection(feedback))
        val guidance = DialogueMoveEngine.plannerConstraint(feedback)
        assertTrue(guidance.contains("不要求等用户再次发出指令"))
        val action = DialogueMoveEngine.resolve(
            JSONObject().put("type", "tease").put("contentIntent", "自己想办法做一个温柔的逗乐尝试"),
            "尝试让她笑一下",
            feedback,
        )
        assertEquals(DialogueMoveType.TEASE, action.type)
    }

    @Test fun vagueCorrectionStillRequestsLocalRepair() {
        val correction = "你根本没理解我意思"
        assertTrue(DialogueMoveEngine.userInitiatesRepair(correction))
        assertFalse(DialogueMoveEngine.correctionSuppliesDirection(correction))
        val move = DialogueMoveEngine.resolve(
            JSONObject().put("type", "tease"), "", correction)
        assertEquals(DialogueMoveType.OTHER_INITIATED_REPAIR, move.type)
    }

    @Test fun localMovesCannotExpandIntoThreeBubbleMiniEssays() {
        val backchannel = DialogueMoveEngine.resolve(
            JSONObject().put("type", "backchannel").put("maxBubbles", 3),
            "表示自己还在听",
            "嗯",
        )
        assertEquals(DialogueMoveType.BACKCHANNEL, backchannel.type)
        assertEquals(1, backchannel.maxBubbles)

        val reassure = DialogueMoveEngine.resolve(
            JSONObject().put("type", "reassure").put("maxBubbles", 3),
            "让她知道这件事不用一个人扛",
            "我有点慌",
        )
        assertEquals(2, reassure.maxBubbles)

        val answer = DialogueMoveEngine.resolve(
            JSONObject().put("type", "answer").put("maxBubbles", 3),
            "回答一个确实复杂的问题",
            "你具体怎么想的？",
        )
        assertEquals(3, answer.maxBubbles)
    }

    @Test fun moveSpecificExpressionRulesHaveRealStoppingConditions() {
        val ack = DialogueMoveEngine.expressionConstraint(
            DialogueMovePlan(DialogueMoveType.ACKNOWLEDGE, contentIntent = "确认听见了"),
        )
        assertTrue(ack.contains("确认完成就停"))

        val tease = DialogueMoveEngine.expressionConstraint(
            DialogueMovePlan(DialogueMoveType.TEASE, contentIntent = "顺手逗她一下"),
        )
        assertTrue(tease.contains("玩笑落地就停"))

        val close = DialogueMoveEngine.expressionConstraint(
            DialogueMovePlan(DialogueMoveType.CLOSE, contentIntent = "结束这一拍"),
        )
        assertTrue(close.contains("不追加新问题"))
    }
}
