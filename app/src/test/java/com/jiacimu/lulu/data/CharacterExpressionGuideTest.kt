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
}
