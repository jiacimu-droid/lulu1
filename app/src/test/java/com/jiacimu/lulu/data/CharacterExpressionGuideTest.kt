package com.jiacimu.lulu.data

import android.content.Context
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
