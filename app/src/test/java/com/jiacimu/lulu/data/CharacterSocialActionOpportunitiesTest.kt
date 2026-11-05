package com.jiacimu.lulu.data

import android.content.Context
import org.json.JSONObject
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

    @Test fun chatImageIsOfferedAsAnAlbumPhotoInsteadOfEncodedUserWords() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        val role = MigratedDomainStores.characters.create(
            "图片收藏与相册测试-" + java.util.UUID.randomUUID(), "珍惜收到的照片")
        val other = MigratedDomainStores.characters.create(
            "不可访问别人图片-" + java.util.UUID.randomUUID(), "独立")
        val convo = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val original = java.io.File(ctx.filesDir, "album-chat-" + java.util.UUID.randomUUID() + ".png")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            original.outputStream().use { assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
            val encoded = com.jiacimu.lulu.encodeQqChatImage(
                android.net.Uri.fromFile(original).toString(),
                caption = "我画的小猫", imageDescription = "纸上的涂鸦")
            val message = MigratedDomainStores.chat.sendUserMessage(convo.id, encoded)
            val view = CharacterSocialActionOpportunities.prompt(role.characterId)
            assertTrue(view.contains("messageId=${message.id}"))
            assertTrue(view.contains("聊天图片"))
            assertTrue(view.contains("我画的小猫"))
            assertTrue(view.contains("save_photo"))
            assertFalse(view.contains("⟪CHAT_IMAGE:"))
            assertFalse(view.contains("eyJ1cmki"))
            assertFalse(CharacterSocialActionOpportunities.prompt(other.characterId)
                .contains("messageId=${message.id}"))

            // The already-existing real executor accepts precisely the same message ID.
            val saved = com.jiacimu.lulu.RolePhotoAlbumStore.saveChatMessage(
                ctx, role.characterId, message.id)
            assertEquals("我画的小猫", saved.caption)
            assertTrue(java.io.File(android.net.Uri.parse(saved.uri).path!!).isFile)
            assertTrue(com.jiacimu.lulu.RolePhotoAlbumStore.list(ctx, other.characterId).isEmpty())

            // An explicitly chosen chat keepsake remains a separate action. It stores
            // the COMPLETE media reference, but its event text must stay readable.
            val receipt = CharacterMessageFavorites.favoriteExplicit(
                role.characterId, message, "这张图是今天收到的惊喜")
            assertNotNull(receipt)
            val favoriteId = CharacterMessageFavorites.entryId(role.characterId, message.id)
            assertTrue(LuluRepositories.lexicon.snapshot(role.characterId)
                .any { it.id == favoriteId && it.content.contains(encoded) })
            assertTrue(SharedExperienceTimeline.eventsByIds(role.characterId, listOf(receipt!!))
                .none { it.content.contains("⟪CHAT_IMAGE:") })
        } finally {
            bitmap.recycle()
            original.delete()
        }
    }

    @Test fun anExecutedRemarkActionPersistsAndTheInspectedPhoneUsesIt() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        CharacterLifeStore.initialize(ctx)
        val character = MigratedDomainStores.characters.create(
            "备注执行测试-" + java.util.UUID.randomUUID(), "重视私人称呼")
        val chat = MigratedDomainStores.chat.ensureConversation(
            character.characterId, character.displayName)
        val before = com.jiacimu.lulu.inspectedThreads(
            character.characterId, listOf(chat), emptyList(), emptyMap())
        assertEquals("主人", before.single().title)

        val receipt = CompanionActionRuntime.execute(ctx, character.characterId,
            "set_user_remark", JSONObject().put("nickname", "佳辞"))
        assertTrue(receipt.summary, receipt.success)
        val actual = CharacterLifeStore.state(character.characterId)
            .optJSONObject("socialNames")?.optString("userRemark").orEmpty()
        assertEquals("佳辞", actual)
        assertTrue(receipt.summary.contains("佳辞"))
        val ownerContact = com.jiacimu.lulu.inspectedOwnerContactName(actual, "主人")
        assertEquals("佳辞", com.jiacimu.lulu.inspectedThreads(
            character.characterId, listOf(chat), emptyList(), emptyMap(), ownerContact
        ).single().title)
        val repeated = CompanionActionRuntime.execute(ctx, character.characterId,
            "set_user_remark", JSONObject().put("nickname", "佳辞"))
        assertFalse(repeated.success) // Do not pretend an unchanged remark was a new action.
        assertEquals("佳辞", CharacterLifeStore.state(character.characterId)
            .getJSONObject("socialNames").getString("userRemark"))
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
        val entryId = CharacterMessageFavorites.entryId(character.characterId, voice.id)
        assertTrue(LuluRepositories.lexicon.snapshot(character.characterId)
            .any { it.id == entryId && it.content.contains(voice.content) })
        assertFalse(CharacterSocialActionOpportunities.prompt(character.characterId)
            .contains("messageId=${voice.id}"))
        val receipts = MigratedDomainStores.chat.messages(conversation.id).value
            .filter { it.sender == LuluChatMessage.Sender.System &&
                it.content.contains("收藏了一条消息") }
        assertEquals(1, receipts.size)
        assertTrue(receipts.single().content.contains("[收藏记录|$entryId]"))
    }

    @Test fun twoIndependentLifeArchivesCanFavoriteTheSameWitnessedMessage() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        val a = MigratedDomainStores.characters.create(
            "收藏互不串线甲-" + java.util.UUID.randomUUID(), "细腻")
        val b = MigratedDomainStores.characters.create(
            "收藏互不串线乙-" + java.util.UUID.randomUUID(), "独立")
        val aChat = MigratedDomainStores.chat.ensureConversation(a.characterId, a.displayName)
        MigratedDomainStores.chat.ensureConversation(b.characterId, b.displayName)
        val message = MigratedDomainStores.chat.sendUserMessage(aChat.id, "大家都听见的这句话")
        // The same delivered group message is independently recorded for B;
        // its original message ID is shared, but its personal evidence ID is not.
        SharedExperienceTimeline.record(
            "${message.id}:group:${b.characterId}", b.characterId, "群聊",
            "用户", message.content, message.createdAt, false,
            source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        val aReceipt = requireNotNull(CharacterMessageFavorites.favoriteExplicit(a.characterId,
            message, "我在意这句话的温柔"))
        val bReceipt = requireNotNull(CharacterMessageFavorites.favoriteExplicit(b.characterId,
            message, "我在意这句话的勇气"))
        assertNotEquals(aReceipt, bReceipt)
        val aEntry = CharacterMessageFavorites.entryId(a.characterId, message.id)
        val bEntry = CharacterMessageFavorites.entryId(b.characterId, message.id)
        assertNotEquals(aEntry, bEntry)
        assertEquals(1, LuluRepositories.lexicon.snapshot(a.characterId)
            .count { it.section == com.jiacimu.lulu.core.LexiconSection.Favorite && it.id == aEntry })
        assertEquals(1, LuluRepositories.lexicon.snapshot(b.characterId)
            .count { it.section == com.jiacimu.lulu.core.LexiconSection.Favorite && it.id == bEntry })
        assertEquals(1, SharedExperienceTimeline.eventsByIds(a.characterId, listOf(aReceipt)).size)
        assertEquals(1, SharedExperienceTimeline.eventsByIds(b.characterId, listOf(bReceipt)).size)
        assertTrue(SharedExperienceTimeline.eventsByIds(a.characterId, listOf(bReceipt)).isEmpty())
        // Deleting one person's saved keepsake and its evidence cannot erase
        // the other character's entry, timeline or reason.
        LuluRepositories.lexicon.delete(aEntry)
        assertTrue(SharedExperienceTimeline.eventsByIds(a.characterId, listOf(aReceipt)).isEmpty())
        assertEquals(1, SharedExperienceTimeline.eventsByIds(b.characterId, listOf(bReceipt)).size)
        assertTrue(LuluRepositories.lexicon.snapshot(b.characterId).any { it.id == bEntry })
        // Re-favoriting must use new evidence rather than the deleted tombstone.
        val fresh = requireNotNull(CharacterMessageFavorites.favoriteExplicit(a.characterId,
            message, "重看以后我有了新的理解"))
        assertNotEquals(aReceipt, fresh)
        assertEquals(1, SharedExperienceTimeline.eventsByIds(a.characterId, listOf(fresh)).size)
    }

    @Test fun unwitnessedForeignPrivateMessageCannotEnterPersonalFavoriteArchive() = kotlinx.coroutines.runBlocking {
        val ctx = RuntimeEnvironment.getApplication() as Context
        LuluRepositories.initialize(ctx)
        SharedExperienceTimeline.initialize(ctx)
        MigratedDomainStores.initialize(ctx)
        val a = MigratedDomainStores.characters.create(
            "收藏私聊甲-" + java.util.UUID.randomUUID(), "安静")
        val b = MigratedDomainStores.characters.create(
            "收藏私聊乙-" + java.util.UUID.randomUUID(), "谨慎")
        val privateChat = MigratedDomainStores.chat.ensureConversation(a.characterId, a.displayName)
        val secret = MigratedDomainStores.chat.sendUserMessage(privateChat.id, "这句话只告诉甲")
        assertNull(CharacterMessageFavorites.favoriteExplicit(b.characterId, secret, "偷存"))
        assertFalse(LuluRepositories.lexicon.snapshot(b.characterId).any {
            it.section == com.jiacimu.lulu.core.LexiconSection.Favorite &&
                it.content.contains("这句话只告诉甲") })
        assertTrue(SharedExperienceTimeline.all(b.characterId).none {
            it.channel == "收藏" && it.content.contains("这句话只告诉甲") })
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
