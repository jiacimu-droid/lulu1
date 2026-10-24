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
    @Test fun sharedDecisionsCanDrawIndependentThoughtsFromSeveralPersonalityFacets() {
        val direct = CharacterDecisionProtocol.runtimePrinciples
        val common = CharacterDecisionProtocol.causalPrinciples
        assertTrue(direct.contains("关系、性格的不同侧面"))
        assertTrue(direct.contains("没有新用户消息"))
        assertTrue(direct.contains("appraisal.privateIdeas"))
        assertTrue(common.contains("横向展开"))
        assertTrue(common.contains("不是固定输出清单"))
        assertTrue(common.contains("无新消息"))
    }

    @Test fun autonomousLifeOffersExplorationWithoutPretendingSearchHasHappened() {
        val p = proactiveDecisionInstruction("independent-topic-test")
        assertTrue(p.contains("web_search"))
        assertTrue(p.contains("read_xiaohongshu"))
        assertTrue(p.contains("image_search"))
        assertTrue(p.contains("搜索结果标题不是已读"))
        assertTrue(p.contains("不必等用户问到这个话题"))
    }

    @Test fun autonomousDiscoveryReadsRegisteredToolLinesWithoutInventingPermissions() {
        val catalog = listOf(
            "- web_search：已接通外部文本检索",
            "- image_search 目前没有配置密钥",
            "- read_xiaohongshu：已允许读取小红书",
            "  当前可复用阅读会话=false",
            "- send_private_message：向用户发消息",
            "- capture_screen：需要授权",
        ).joinToString("\n")
        val rendered = CompanionActionRuntime.discoveryToolLines(catalog)
        assertTrue(rendered.contains("web_search"))
        assertTrue(rendered.contains("image_search 目前没有配置"))
        assertTrue(rendered.contains("read_xiaohongshu"))
        assertTrue(rendered.contains("当前可复用阅读会话=false"))
        assertFalse(rendered.contains("send_private_message"))
        assertFalse(rendered.contains("capture_screen"))
        assertEquals("", CompanionActionRuntime.discoveryToolLines("- send_private_message"))
    }

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
    @Test fun focusedExpressionKeepsPersonalFeelingWithoutReceivingPsychologicalAnalysisAsAnAssignment() {
        val handoff = CharacterDecisionProtocol.expressionContext(
            JSONObject().put("meaning", "完整的心理归因")
                .put("responseAim", "消除她所有的焦虑")
                .put("commonGroundUpdate", "需要逐项回应的关系分析")
                .put("uncertainty", "不能确定她是否想听建议"),
            JSONObject().put("emotion", JSONObject().put("feeling", "有些担心")
                .put("restraint", "不替她做决定")), "", "", "", includeAppraisalDetails = false)
        assertTrue(handoff.contains("有些担心"))
        assertTrue(handoff.contains("不替她做决定"))
        assertTrue(handoff.contains("不能确定她是否想听建议"))
        assertFalse(handoff.contains("完整的心理归因"))
        assertFalse(handoff.contains("消除她所有的焦虑"))
        assertFalse(handoff.contains("需要逐项回应的关系分析"))
        assertFalse(handoff.contains("可选 appraisal.expression"))
    }
    @Test fun complicatedUnspokenConflictCanRemainLayeredWithoutForcingEveryThoughtLong() {
        val basis = JSONObject().put("focus", "刚才的玩笑") 
            .put("conflict", "想接梗，但又怕她刚才不高兴")
            .put("unsaidWhy", "还没决定该不该说")
        val inner = JSONObject().put("emotion", JSONObject()
            .put("feeling", "有些雀跃").put("cause", "想到了有趣的回应")
            .put("otherFeeling", "又担心破坏此刻气氛"))
        val delta = PrivateStateDelta(
            focusChanged = true, appraisalChanged = false, emotionChanged = true,
            motiveChanged = false, conflictChanged = true,
            commonGroundChanged = false, privateResidue = true, fingerprint = "mixed-1",
        )
        val thought = "我其实很想把这个玩笑接下去，但她刚才那句又让我拿不准。我也不是真的非要逗她笑，只是脑子里突然冒出了一个念头，觉得会很有趣。" +
            "现在想想，或许先看她还有没有继续聊的兴致会更好。不过这不意味着我真的想把这份好奇压下去，我只是还没决定。其实我也有点不服气，明明是件小事，我为什么非要想那么多？可再往下想，又觉得这种迟疑确实和我们刚才发生的事有关。也许我最后还是会忍不住接那个梗。"
        assertTrue(thought.length > 140)
        val layered = CharacterHeartVoicePolicy.keepOrBlank(
            thought = thought, innerLife = inner, basis = basis, delta = delta, hasFreshEvidence = true)
        assertTrue(layered.length > 140)
        assertTrue(layered.length <= 320)
        val quick = CharacterHeartVoicePolicy.keepOrBlank(
            thought = thought, basis = JSONObject().put("focus", "刚才那句话").put("unsaidWhy", "还没说"),
            delta = delta, hasFreshEvidence = true)
        assertTrue(quick.length <= 141)
    }
    @Test fun aGenuinePlayfulInnerSceneCanUnfoldWithoutTurningIntoAnAnswer() {
        val basis = JSONObject()
            .put("focus", "对方抛来一个无厘头的问题")
            .put("change", "从惊讶到试着接梗")
            .put("unsaidWhy", "心里还在权衡一个没说出来的小玩笑")
            .put("spontaneousTurn", "先认真想了想，又突然有个调皮的念头，自己觉得好笑")
        val delta = PrivateStateDelta(
            focusChanged = true, appraisalChanged = false, emotionChanged = false,
            motiveChanged = false, conflictChanged = false, commonGroundChanged = false,
            privateResidue = true, fingerprint = "funny-pivot",
        )
        val thought = listOf(
            "哎这个问题真能问出来。怎么想到的？",
            "先想想，正经回答好像也有道理，不过怎么都觉得少点什么。",
            "等等……我突然想到一个离谱答案。要是真说出来她会不会笑？",
            "不对不对，我好像更想看她听到以后是什么反应。算了，试一下？",
        ).joinToString("\n\n").repeat(4)
        val kept = CharacterHeartVoicePolicy.keepOrBlank(
            thought = thought, outward = "我有个答案。",
            basis = basis, delta = delta, hasFreshEvidence = true,
        )
        assertTrue(kept.length > 320)
        assertTrue(kept.length <= 600)
        assertTrue(kept.contains("\n\n"))
        val unchanged = CharacterHeartVoicePolicy.keepOrBlank(
            thought = thought, outward = "我有个答案.", basis = basis,
            delta = delta.copy(focusChanged = false, privateResidue = true,
                appraisalChanged = false, emotionChanged = false),
            hasFreshEvidence = true,
        )
        assertEquals("", unchanged)
    }

    @Test fun imageOnlyReplyIsAValidDecisionButStillNeedsARealStickerId() {
        assertEquals("reply", CharacterDecisionProtocol.chatAction(JSONObject()
            .put("action", "reply").put("stickerOnly", true).put("stickerId", "actual-shelf-id")))
        assertNull(CharacterDecisionProtocol.chatAction(JSONObject()
            .put("action", "reply").put("stickerOnly", true)))
    }
}
