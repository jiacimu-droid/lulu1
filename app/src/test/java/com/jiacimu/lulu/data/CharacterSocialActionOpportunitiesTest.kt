package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterSocialActionOpportunitiesTest {
    @Test fun modelCanSelectActualUnfavoritedUserWordsAndSeeRealNames() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        val role = MigratedDomainStores.characters.create("社交选择测试角色", "注重细节")
        val privateChat = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val message = MigratedDomainStores.chat.sendUserMessage(privateChat.id, "我觉得你刚刚那个冷笑话其实很好笑")
        CharacterLifeStore.setSocialName(role.characterId, "userRemark", "我的小搭档")
        CharacterLifeStore.setSocialName(role.characterId, "selfNickname", "晚风")
        val view = CharacterSocialActionOpportunities.prompt(role.characterId)
        assertTrue(view.contains("messageId=${message.id}"))
        assertTrue(view.contains("我觉得你刚刚那个冷笑话其实很好笑"))
        assertTrue(view.contains("我的小搭档"))
        assertTrue(view.contains("晚风"))
        assertTrue(view.contains("不需要"))
    }

    @Test fun phoneTranscriptOffersAnActuallyStoredMessageIdForImmediateFavorite() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        val character = MigratedDomainStores.characters.create("电话收藏测试", "喜欢珍藏有趣对话")
        val conversation = MigratedDomainStores.chat.ensureConversation(
            character.characterId, character.displayName,
        )
        val voice = MigratedDomainStores.chat.appendVoiceMessage(
            conversation.id, "voice-test-favorite-user-1", "我其实一直记得你那句冷笑话", false,
        )
        val candidates = CharacterSocialActionOpportunities.prompt(character.characterId)
        assertTrue(candidates.contains("messageId=${voice.id}"))
        val receipt = CharacterMessageFavorites.favoriteExplicit(
            character.characterId, voice, "因为她还记得那个笑话，我想珍藏这句话",
        )
        assertNotNull(receipt)
        assertTrue(LuluRepositories.lexicon.snapshot(character.characterId)
            .any { it.id == "message-${voice.id}" && it.content.contains(voice.content) })
        assertFalse(CharacterSocialActionOpportunities.prompt(character.characterId)
            .contains("messageId=${voice.id}"))
        val receipts = MigratedDomainStores.chat.messages(conversation.id).value
            .filter { it.sender == LuluChatMessage.Sender.System &&
                it.content.contains("收藏了一条消息") }
        assertEquals(1, receipts.size)
        assertTrue(receipts.single().content.contains("[收藏记录|message-${voice.id}]"))
    }

    @Test fun onlyRealUnfinishedPhoneFavoriteWishBecomesAConcern() {
        val pending = CharacterDeferredFavoritePolicy.concern(
            "我想收藏她刚才那句话，可还不知道具体是哪条", "",
            hasVerifiedUserMessage = true,
        )
        assertNotNull(pending)
        assertEquals("phone-deferred-favorite", pending?.getString("topicKey"))
        assertTrue(pending!!.getString("possibleNextStep").contains("成功回执前"))
        assertNull(CharacterDeferredFavoritePolicy.concern(
            "我想收藏她刚才那句话", "", hasVerifiedUserMessage = false,
        ))
        assertNull(CharacterDeferredFavoritePolicy.concern(
            "我不想收藏她的消息", "", hasVerifiedUserMessage = true,
        ))
        assertNull(CharacterDeferredFavoritePolicy.concern(
            "", "可以收藏我的消息吗？", hasVerifiedUserMessage = true,
        ))
        assertNotNull(CharacterDeferredFavoritePolicy.concern(
            "", "等这通电话结束之后再收藏她刚才那句话",
            hasVerifiedUserMessage = true,
        ))
    }

    @Test fun unrelatedCharacterCannotSeeAnotherRolesPrivateCandidates() {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        val owner = MigratedDomainStores.characters.create("私聊归属甲", "话少")
        val other = MigratedDomainStores.characters.create("私聊归属乙", "话多")
        val chat = MigratedDomainStores.chat.ensureConversation(owner.characterId, owner.displayName)
        val secret = MigratedDomainStores.chat.sendUserMessage(chat.id, "只有甲知道的私人话题")
        val otherContext = CharacterSocialActionOpportunities.prompt(other.characterId)
        assertFalse(otherContext.contains(secret.id))
        assertFalse(otherContext.contains("只有甲知道的私人话题"))
    }
}
