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
        val guidance = DialogueMoveEngine.plannerConstraint(feedback)
        assertTrue(guidance.contains("自主判断"))
        val action = DialogueMoveEngine.resolve(
            JSONObject().put("type", "tease").put("contentIntent", "自己想办法做一个温柔的逗乐尝试"),
            "尝试让她笑一下",
            feedback,
        )
        assertEquals(DialogueMoveType.TEASE, action.type)
    }

    @Test fun userWordsCannotOverrideTheModelsExplicitDialogueMove() {
        val correction = "你根本没理解我意思"
        assertEquals(DialogueMoveType.TEASE, DialogueMoveEngine.resolve(
            JSONObject().put("type", "tease"), "", correction).type)
        assertEquals(DialogueMoveType.OTHER_INITIATED_REPAIR, DialogueMoveEngine.resolve(
            JSONObject().put("type", "other_initiated_repair"), "", "晚饭吃了吗").type)
        val accepted = DialogueMoveEngine.resolve(
            JSONObject().put("type", "acknowledge").put("commonGroundDecision", "accept"),
            "对，我懂了", "你弄错了")
        assertEquals("accept", accepted.groundingDecision)
        assertEquals("", DialogueMoveEngine.resolve(
            JSONObject().put("type", "answer"), "", "对！").groundingDecision)
    }

    @Test fun localMovesCannotExpandIntoThreeBubbleMiniEssays() {
        val backchannel = DialogueMoveEngine.resolve(
            JSONObject().put("type", "backchannel").put("maxBubbles", 3),
            "表示自己还在听",
            "嗯",
        )
        assertEquals(DialogueMoveType.BACKCHANNEL, backchannel.type)
        assertEquals(3, backchannel.maxBubbles)

        val reassure = DialogueMoveEngine.resolve(
            JSONObject().put("type", "reassure").put("maxBubbles", 3),
            "让她知道这件事不用一个人扛",
            "我有点慌",
        )
        assertEquals(3, reassure.maxBubbles)

        val answer = DialogueMoveEngine.resolve(
            JSONObject().put("type", "answer").put("maxBubbles", 3),
            "回答一个确实复杂的问题",
            "你具体怎么想的？",
        )
        assertEquals(3, answer.maxBubbles)
    }

    @Test fun modelChosenBubbleCountIsNotCutByAProgramTypeTable() {
        val result = DialogueMoveEngine.resolve(
            JSONObject().put("type", "other_initiated_repair")
                .put("maxBubbles", 4), "有复杂背景需要澄清", "不是这个意思")
        assertEquals(4, result.maxBubbles)
    }

    @Test fun chosenMoveIsPreservedWhilePersonOwnsItsActualExpression() {
        DialogueMoveType.values().forEach { type ->
            val plan = DialogueMovePlan(type, contentIntent = "这次自己的意思", maxBubbles = 4)
            val prompt = DialogueMoveEngine.expressionConstraint(plan)
            assertTrue(prompt.contains(type.wire))
            assertTrue(prompt.contains("这次自己的意思"))
            assertTrue(prompt.contains("具体态度承接当前设定与感受"))
            assertFalse(prompt.contains("严禁"))
            assertFalse(prompt.contains("不总结、不建议"))
            assertFalse(prompt.contains("呼吸练习"))
            assertEquals(4, plan.maxBubbles)
        }
    }
}
