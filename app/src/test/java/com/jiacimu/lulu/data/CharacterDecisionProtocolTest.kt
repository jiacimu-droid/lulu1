package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterDecisionProtocolTest {
    @Test fun quietDecisionIsValidWithoutSpeech() {
        val quiet = JSONObject("""{"action":"silent","reason":"我现在需要自己想想"}""")
        assertEquals("silent", CharacterDecisionProtocol.chatAction(quiet))
        assertNull(ModelStructuredOutput.completedReplyText(quiet.toString()))
    }

    @Test fun aReplyCanBeDecidedBeforeItsWording() {
        val plan = JSONObject("""{"action":"reply","speechIntent":"反过来问她为什么这样想"}""")
        assertEquals("reply", CharacterDecisionProtocol.chatAction(plan))
        assertEquals("反过来问她为什么这样想", CharacterDecisionProtocol.speechIntent(plan))
        assertTrue(CharacterDecisionProtocol.usesSeparateExpression("正在私聊"))
        assertFalse(CharacterDecisionProtocol.usesSeparateExpression("正在电话中"))
    }

    @Test fun userCorrectionForcesLowCostRepairMove() {
        val playfulProposal = JSONObject()
            .put("type", "tease")
            .put("candidate", "你是想让我直接打电话过去？")
            .put("confidence", 0.41)
        val move = DialogueMoveEngine.resolve(
            playfulProposal,
            "继续逗她并猜她真正想表达什么",
            "你没有get到我什么意思",
        )
        assertEquals(DialogueMoveType.OTHER_INITIATED_REPAIR, move.type)
        assertEquals(RepairFormat.OPEN, move.repairFormat)
        assertEquals(1, move.maxBubbles)
        assertEquals("", move.candidate)
        assertTrue(DialogueMoveEngine.expressionConstraint(move).contains("不要枚举第二个候选"))
    }

    @Test fun onlyStrongSingleCandidateMayBecomeCandidateRepair() {
        val proposal = JSONObject()
            .put("type", "candidate_understanding")
            .put("candidate", "你是说我应该直接打过去？")
            .put("confidence", 0.82)
        val move = DialogueMoveEngine.resolve(proposal, "", "不是，你没get到我意思")
        assertEquals(DialogueMoveType.CANDIDATE_UNDERSTANDING, move.type)
        assertEquals(RepairFormat.CANDIDATE, move.repairFormat)
        assertEquals("你是说我应该直接打过去？", move.candidate)
    }

    @Test fun repeatingSamePrivateStateIsNotANewDelta() {
        val basis = JSONObject().put("focus", "用户刚才否定了我的理解")
            .put("unsaidWhy", "先不把尴尬说出口")
        val appraisal = JSONObject().put("meaning", "她在纠正我的误解")
            .put("responseAim", "先修复")
            .put("interactionMove", "repair")
        val emotion = JSONObject().put("feeling", "有点尴尬")
            .put("cause", "意识到自己理解错了").put("strength", 2)
        val previous = JSONObject()
            .put("emotion", JSONObject(emotion.toString()))
            .put("causalTransitions", org.json.JSONArray().put(JSONObject()
                .put("innerThoughtBasis", JSONObject(basis.toString()))
                .put("appraisal", JSONObject(appraisal.toString()))))
        val same = PrivateStateDeltaEngine.evaluate(
            previous, JSONObject().put("emotion", JSONObject(emotion.toString())),
            appraisal, basis, "刚才确实是我会错意了",
        )
        assertFalse(same.meaningful)
        val changedEmotion = JSONObject(emotion.toString()).put("feeling", "松了口气")
        val changed = PrivateStateDeltaEngine.evaluate(
            previous, JSONObject().put("emotion", changedEmotion),
            appraisal, basis, "好，原来是这样",
        )
        assertTrue(changed.meaningful)
        assertTrue(changed.emotionChanged)
    }

    @Test fun aGroupIsQuietOnlyWhenSilenceIsExplicitlyChosen() {
        assertTrue(CharacterDecisionProtocol.groupIsExplicitlySilent("""{"action":"silent","reason":"大家都在忙","turns":[]}"""))
        assertFalse(CharacterDecisionProtocol.groupIsExplicitlySilent("""{"turns":[]}"""))
        assertFalse(CharacterDecisionProtocol.groupIsExplicitlySilent("""{"action":"silent","turns":[{"characterId":"a"}]}"""))
    }

    @Test fun invalidAndUnknownToolsAreNotValidChoices() {
        assertNull(CharacterDecisionProtocol.chatAction(JSONObject("""{"action":"tool","text":"已经完成"}""")))
        assertNull(CharacterDecisionProtocol.chatAction(JSONObject("""{"action":"delete_everything","text":"完成"}""")))
        assertEquals("tool", CharacterDecisionProtocol.chatAction(JSONObject("""{"action":"tool","tool":"get_battery","args":{}}""")))
    }
    @Test fun leanExpressionKeepsMeaningEmotionAndRelationshipInsteadOfOnlySpeechIntent() {
        val handoff = CharacterDecisionProtocol.expressionContext(
            JSONObject().put("meaning", "她希望我也主动联系").put("responseAim", "回应她的失望"),
            JSONObject().put("emotion", JSONObject().put("feeling", "有些歉疚")
                .put("cause", "确实没主动打电话").put("restraint", "不乱许诺")),
            "", "之前还惦记着她", "日常称呼=宝宝")
        assertTrue(handoff.contains("她希望我也主动联系"))
        assertTrue(handoff.contains("有些歉疚"))
        assertTrue(handoff.contains("不乱许诺"))
        assertTrue(handoff.contains("日常称呼=宝宝"))
    }
}
