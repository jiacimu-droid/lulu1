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
@Config(manifest = Config.NONE, sdk = [30])
class CharacterExpressionGuideTest {
    @Test fun innerVoiceIsOnlyWhatTheCharacterHasNotSaidOutLoud() {
        val prompt = spontaneousInnerVoiceGuide
        assertTrue(prompt.contains("心声是没有说出口、留在心里的想法"))
        assertTrue(prompt.contains("已经写进 text"))
        assertTrue(prompt.contains("不能将发言换种措辞复述一遍"))
        assertTrue(prompt.contains("如果没有，就返回空字符串"))
        assertTrue(prompt.contains("同一件事有不同的外在表达和内在想法"))
        assertTrue(prompt.contains("活着的内心语感"))
        assertTrue(prompt.contains("spontaneousTurn"))
        assertTrue(prompt.contains("不是模型工作推理的展示"))
    }

    @Test fun decisionHandoffDoesNotPreWriteTheCharactersStyle() {
        assertTrue(CharacterDecisionProtocol.principles.contains("不要预先规定昵称"))
        val handoff = CharacterDecisionProtocol.expressionContext(
            appraisal = null,
            innerLife = null,
            mood = "有点在意",
            continuousState = "",
            relationshipState = "",
        )
        assertTrue(handoff.contains("称呼、句式、停顿、玩笑与修辞由表达层"))
        assertTrue(spontaneousInnerVoiceGuide.contains("持续感知不等于持续写独白"))
    }

    @Test fun emotionalSubtextSurvivesTheLeanPlannerToRendererHandoff() {
        val appraisal = JSONObject()
            .put("responseAim", "接住对方的疲惫，不把倾诉变成作息指导")
            .put("expression", JSONObject()
                .put("stance", "心疼又有点愧疚")
                .put("subtext", "在意她为这段关系做的努力")
                .put("device", "一句轻声的接话")
                .put("restraint", "不把所有理由说透"))
        val handoff = CharacterDecisionProtocol.expressionContext(
            appraisal, null, "", "", "", includeAppraisalDetails = false,
        )
        assertTrue(handoff.contains("接住对方的疲惫"))
        assertTrue(handoff.contains("心疼又有点愧疚"))
        assertTrue(handoff.contains("在意她为这段关系做的努力"))
        assertTrue(handoff.contains("有意留白"))
        assertFalse(handoff.contains("自己选的表达倾向：；"))
        val rendering = CharacterExpressionGuide.forGeneration("聊天表达渲染")
        val repair = CharacterExpressionGuide.forGeneration("聊天表达自然度修复")
        assertTrue(rendering.contains("情绪潜台词"))
        assertTrue(repair.contains("情绪潜台词"))
        assertTrue(CharacterDecisionProtocol.runtimePrinciples.contains("appraisal.expression"))
        assertTrue(CharacterDecisionProtocol.principles.contains("subtext"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PHONE).contains("话外音"))
        assertFalse(rendering.contains("是我太坏了"))
    }

    @Test fun missingExpressionStateDoesNotManufactureEmotionsOrForceBriefReplies() {
        val handoff = CharacterDecisionProtocol.expressionContext(
            null, null, "", "", "", includeAppraisalDetails = false,
        )
        assertFalse(handoff.contains("角色自选的情绪表达线索"))
        val principles = CharacterExpressionGuide.subtextPrinciples
        assertTrue(principles.contains("不必把一切说透"))
        assertTrue(principles.contains("有时需要认真解释"))
        assertTrue(principles.contains("不模仿固定示例"))
        assertTrue(principles.contains("独立一张恰当的表情包"))
    }

    @Test fun sharedPrinciplesLeaveRoomForBothBriefAndExpressiveChat() {
        val prompt = CharacterExpressionGuide.promptSection()
        assertTrue(prompt.contains("表达已经足够时可以直接停下来"))
        assertTrue(prompt.contains("文采是能力，不是义务"))
        assertTrue(prompt.contains("真有话想抒发时也不必压成刻意简短的句子"))
        assertTrue(prompt.contains("个人语言会成长，但不自我复制"))
        assertFalse(prompt.contains("每条不超过"))
    }

    @Test fun oneSpeakerIdentityRuleSurvivesEveryDeliveryChannel() {
        val channels = CharacterSpeechChannel.values().toList()
        assertEquals(6, channels.size)
        channels.forEach { channel ->
            val guidance = CharacterExpressionGuide.channelNote(channel)
            assertTrue("Missing shared identity for $channel",
                guidance.contains("【同一人物的跨场景说话准则】"))
            assertTrue(guidance.contains("稳定称呼"))
            assertTrue(guidance.contains("真正想说的重点"))
            assertFalse(guidance.contains("每条不超过"))
        }
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.PHONE)
            .contains("实时通话优先低延迟、可打断"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.MEETING)
            .contains("动作、环境和内心独白放在各自的现场叙述"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.GROUP_CHAT)
            .contains("每位成员保持自己的一套语言偏好"))
        assertTrue(CharacterExpressionGuide.channelNote(CharacterSpeechChannel.TOOL_RESULT)
            .contains("真实回执"))
    }

    @Test fun distinctMembersKeepDistinctConfiguredVoicesInSharedGroupOrCall() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        val first = MigratedDomainStores.characters.create("风格甲", "说话直接")
        val second = MigratedDomainStores.characters.create("风格乙", "说话温吞")
        CharacterLifeStore.setProfile(first.characterId, "speechHabits", "爱说冷笑话的独特节奏")
        CharacterLifeStore.setProfile(second.characterId, "speechHabits", "说话细致温柔但不绕弯")
        val firstVoice = CharacterSpeechIdentity.promptSection(first.characterId)
        val secondVoice = CharacterSpeechIdentity.promptSection(second.characterId)
        assertTrue(firstVoice.contains("爱说冷笑话的独特节奏"))
        assertFalse(firstVoice.contains("说话细致温柔但不绕弯"))
        assertTrue(secondVoice.contains("说话细致温柔但不绕弯"))
        assertFalse(secondVoice.contains("爱说冷笑话的独特节奏"))
    }

    @Test fun onlineUnreadPrivateChatUsesPrivateSpeechChannelRatherThanAutonomousBroadcast() {
        val freshPrivate = proactiveDecisionInstruction("ordinary-role-without-life", directPrivateChat = true)
        val autonomous = proactiveDecisionInstruction("ordinary-role-without-life")
        assertTrue(freshPrivate.contains("【私聊呈现】像熟人一对一接话"))
        assertFalse(freshPrivate.contains("【自主分享呈现】"))
        assertTrue(autonomous.contains("【自主分享呈现】"))
        assertFalse(autonomous.contains("【私聊呈现】"))
        assertTrue(freshPrivate.contains("优先选择对应的真实 action"))
        assertTrue(freshPrivate.contains("innerThoughtBasis"))
        // The current online decision protocol executes ordered steps with
        // independent receipts; the former sideActions wording is obsolete.
        assertTrue(freshPrivate.contains("steps=["))
        assertTrue(freshPrivate.contains("requiresPreviousSuccess"))
        assertTrue(freshPrivate.contains("一个完整 JSON"))
        assertTrue(freshPrivate.length < autonomous.length)
        // The self-directed life instructions still exist for genuinely
        // autonomous turns; they no longer burden a casual incoming message.
        assertTrue(autonomous.contains("【本轮自主决策】"))
        assertFalse(freshPrivate.contains("【本轮自主决策】"))
    }

    @Test fun voluntaryFuturePlanningIsAvailableInTheSameLifeDecisionWithoutForcingADailyChecklist() {
        val inChat = proactiveDecisionInstruction("role-without-life", directPrivateChat = true)
        val onOwn = proactiveDecisionInstruction("role-without-life", directPrivateChat = false)
        assertTrue(inChat.contains("tool=plan_personal_activity"))
        assertTrue(inChat.contains("同一轮 steps 中使用"))
        assertTrue(inChat.contains("不必等主人给出具体时间"))
        assertTrue(inChat.contains("commitmentTaskId"))
        assertTrue(inChat.contains("不需要为每次聊天刻意制造一份日程"))
        assertTrue(onOwn.contains("现在做一件事，又想为稍后排另一件事"))
        assertTrue(onOwn.contains("action=tool, tool=plan_personal_activity"))
        assertTrue(onOwn.contains("不是回复或现在行动的互斥选项"))
        assertTrue(onOwn.contains("已有 planId"))
        assertTrue(onOwn.contains("不能为了显得活跃填满日程"))
    }

    @Test fun directPrivateReplyIsSpokenConversationNotAnExhaustivePlanningReport() {
        val shortExchange = privateConversationInstruction("那宝宝喜欢哪个？给你发了六个", "之前已经看了发来的图")
        assertTrue(shortExchange.contains("text 会原样进入聊天气泡"))
        assertTrue(shortExchange.contains("已说过的事实不必先复述确认"))
        assertTrue(shortExchange.contains("也不必一口气做总评"))
        assertTrue(shortExchange.contains("同一轮拆成几个气泡也仍只是这一轮发言"))
        assertTrue(shortExchange.contains("认真请教、要完整分析"))
        assertTrue(shortExchange.contains("不改变已有可选行动"))
        assertFalse(shortExchange.contains("最多回复"))
    }

    @Test fun situationalPlayfulnessIsAChoiceNotAFlirtingTemplate() {
        val expression = CharacterExpressionGuide.promptSection()
        val initiative = proactiveDecisionInstruction("ordinary-role-without-life")
        assertTrue(expression.contains("临场生动感"))
        assertTrue(expression.contains("不强行搞气氛"))
        assertTrue(initiative.contains("本人自发的小冲动"))
        assertTrue(initiative.contains("优先选择对应的真实 action"))
        assertTrue(CharacterDecisionProtocol.principles.contains("spontaneousBid"))
        val appraisal = JSONObject().put("spontaneousBid", JSONObject()
            .put("spark", "刚才两个人都看见猫把杯子推下桌")
            .put("wantedMove", "顺口吐槽小猫把物理课当实验课"))
        val handoff = CharacterDecisionProtocol.expressionContext(
            appraisal = appraisal, innerLife = null, mood = "",
            continuousState = "", relationshipState = "",
        )
        assertTrue(handoff.contains("顺口吐槽小猫把物理课当实验课"))
        assertTrue(handoff.contains("可收住的意图"))
        assertFalse(handoff.contains("必须复述的台词。\n这轮"))
    }

    @Test fun personalityArchitectureSeparatesStablePersonFromChangingState() {
        val prompt = CharacterPersonalityArchitecture.promptSection()
        assertTrue(prompt.contains("稳定核心"))
        assertTrue(prompt.contains("情境化适应"))
        assertTrue(prompt.contains("叙事身份"))
        assertTrue(prompt.contains("即时状态"))
        assertTrue(prompt.contains("互动状态"))
        assertTrue(prompt.contains("表达实现"))
        assertTrue(prompt.contains("长期概率倾向"))
        assertTrue(CharacterDecisionProtocol.principles.contains("共同语境"))
        assertTrue(CharacterDecisionProtocol.principles.contains("优先完成修复"))
    }

    @Test fun heartVoiceRequiresPrivateCausalResidueInsteadOfASecondAnswer() {
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "还是先等她吧，别催了",
            outward = "",
            innerLife = null,
            hasFreshEvidence = true,
        ))
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "我想先问问你到底怎么想",
            outward = "我想先问问你到底怎么想。",
            innerLife = null,
            hasFreshEvidence = true,
        ))
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "突然想起那本书还没看完",
            outward = "",
            innerLife = null,
            hasFreshEvidence = false,
        ))
        val changed = JSONObject().put("emotion", JSONObject()
            .put("feeling", "有点失落")
            .put("cause", "她明确拒绝了邀约"))
        assertEquals("其实还是有点失落", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "其实还是有点失落",
            outward = "好，那今天就不去了。",
            innerLife = changed,
            hasFreshEvidence = true,
        ))
    }

    @Test fun freshTopicWithAuthoredPrivateInterpretationIsNotSilentlyDropped() {
        val previous = JSONObject().put("causalTransitions", org.json.JSONArray().put(JSONObject()
            .put("innerThoughtBasis", JSONObject().put("focus", "正在聊昨晚睡眠"))
            .put("appraisal", JSONObject().put("meaning", "她在准备休息"))))
        val basis = JSONObject().put("focus", "她忽然开始聊新读到的小说")
            .put("change", "开始好奇这本书的伏笔")
        val appraisal = JSONObject().put("meaning", "这次在聊文学而不是睡眠")
        val delta = PrivateStateDeltaEngine.evaluate(previous, null, appraisal, basis,
            "原来她也会注意到那种伏笔，真想问问后面怎么写的。")
        assertTrue(delta.focusChanged)
        assertTrue(delta.appraisalChanged)
        assertTrue(delta.privateResidue)
        assertEquals("原来她也会注意到那种伏笔，真想问问后面怎么写的。",
            CharacterHeartVoicePolicy.keepOrBlank(
                thought = "原来她也会注意到那种伏笔，真想问问后面怎么写的。",
                outward = "那本小说讲了什么？",
                basis = basis, delta = delta, hasFreshEvidence = true))
        val repeated = PrivateStateDeltaEngine.evaluate(
            JSONObject().put("causalTransitions", org.json.JSONArray().put(JSONObject()
                .put("innerThoughtBasis", basis).put("appraisal", appraisal))),
            null, appraisal, basis, "还是先等等她")
        assertFalse(repeated.meaningful)
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "还是先等等她", basis = basis,
            delta = repeated, hasFreshEvidence = true))
    }

    @Test fun surfacedHeartVoiceStaysCompactAndDoesNotEchoOutwardSpeech() {
        val changed = JSONObject().put("emotion", JSONObject()
            .put("feeling", "委屈")
            .put("cause", "刚刚被误解"))
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "我有点委屈，但还是想先把这件事说明白",
            outward = "我有点委屈，但还是想先把这件事说明白。",
            innerLife = changed,
            hasFreshEvidence = true,
        ))
        val longThought = "其实我还在犹豫。" + "这件事让我有点放不下，".repeat(20)
        val kept = CharacterHeartVoicePolicy.keepOrBlank(
            thought = longThought,
            outward = "嗯。",
            innerLife = changed,
            hasFreshEvidence = true,
        )
        assertTrue(kept.length <= 141)
    }

    @Test fun freshMessageAloneDoesNotAuthorizeAHeartVoice() {
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "她刚才那句话其实让我有点介意",
            outward = "嗯，我知道了。",
            innerLife = null,
            basis = null,
            hasFreshEvidence = true,
        ))
        assertEquals("", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "她刚才那句话其实让我有点介意",
            outward = "嗯，我知道了。",
            innerLife = null,
            basis = JSONObject().put("change", "开始有点介意"),
            hasFreshEvidence = true,
        ))
        val basis = JSONObject()
            .put("focus", "用户刚刚说“算了”")
            .put("change", "我第一次意识到她可能不想继续这个话题")
            .put("conflict", "想问清楚，又不想立刻逼问")
            .put("unsaidWhy", "这部分不适合直接说出口")
        assertEquals("她刚才那句话其实让我有点介意", CharacterHeartVoicePolicy.keepOrBlank(
            thought = "她刚才那句话其实让我有点介意",
            outward = "嗯，我知道了。",
            innerLife = null,
            basis = basis,
            hasFreshEvidence = true,
        ))
    }

    @Test fun situationalPatternNeedsRepeatedEvidence() {
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.SituationalPattern, 1, false, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.SituationalPattern, 2, false, 0))
        assertTrue(DevelopmentPolicy.accepts(DevelopmentKind.SituationalPattern, 3, false, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.SituationalPattern, 3, false, 1))
        assertTrue(CharacterPersonalityArchitecture.promptSection().contains("情境—反应签名"))
    }

    @Test fun narrativeMeaningNeedsMoreThanOneEvent() {
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.NarrativeMeaning, 1, false, 0))
        assertTrue(DevelopmentPolicy.accepts(DevelopmentKind.NarrativeMeaning, 2, false, 0))
        assertFalse(DevelopmentPolicy.accepts(DevelopmentKind.NarrativeMeaning, 2, false, 1))
    }

    @Test fun modelWrittenChatDoesNotBecomeAnUnreviewedStyleExample() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        SharedExperienceTimeline.initialize(context)
        CharacterLifeStore.initialize(context)
        CharacterDevelopmentStore.initialize(context)
        val role = MigratedDomainStores.characters.create("说话指纹回归测试", "自然聊天")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val accidentalStyle = "星河揉碎在指尖，夜色为每一句话写下一篇散文"
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, accidentalStyle, role.characterId)
        val prompt = CharacterSpeechIdentity.promptSection(role.characterId)
        assertFalse(prompt.contains(accidentalStyle))
        assertTrue(prompt.contains("最近消息用来理解上下文，不是语言模仿范本"))

        CharacterLifeStore.setProfile(role.characterId, "speechHabits", "熟悉时说话干脆，会接冷笑话")
        val configured = CharacterSpeechIdentity.promptSection(role.characterId, includeObserved = false)
        assertTrue(configured.contains("熟悉时说话干脆，会接冷笑话"))
        assertFalse(configured.contains(accidentalStyle))
        val noDuplicate = CharacterSpeechIdentity.promptSection(role.characterId, includeConfigured = false)
        assertFalse(noDuplicate.contains("熟悉时说话干脆，会接冷笑话"))
        assertTrue(noDuplicate.contains("最近消息用来理解上下文"))
    }
    @Test fun allCharactersCanNoticeMeaningfulContextWithoutAForcedBedtimeScript() {
        val common = CharacterDecisionProtocol.runtimePrinciples
        val causal = CharacterDecisionProtocol.causalPrinciples
        assertTrue(common.contains("【自主注意力】"))
        assertTrue(common.contains("不要只等待对方把需求说出口"))
        assertTrue(common.contains("没有显著变化可以没有新念头"))
        assertTrue(common.contains("注意到不等于必须提起"))
        assertTrue(causal.contains("主动留意哪些已知事实"))
        assertTrue(causal.contains("不要每次重复首次关心"))
        assertFalse(common.contains("凌晨四点必须"))
    }

}
