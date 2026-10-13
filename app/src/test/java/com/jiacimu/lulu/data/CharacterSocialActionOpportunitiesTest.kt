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
