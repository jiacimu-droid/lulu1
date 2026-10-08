package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class UserMessageFavoritesTest {
    @Test fun originalAudioAndExactTextSurviveChatCacheDeletionAndRestart() {
        val context = RuntimeEnvironment.getApplication() as Context
        val original = File(context.cacheDir, "original.mp3").apply { writeBytes(byteArrayOf(1, 9, 3, 7)) }
        val message = LuluChatMessage(id = "favorite-voice", conversationId = "chat", sender = LuluChatMessage.Sender.Character,
            content = "这句话想留给你。", authorCharacterId = "role", createdAt = Instant.parse("2026-10-08T05:00:00Z"))
        val store = UserMessageFavoriteStore(context)
        assertTrue(store.save(message, "role", "江渡", null, "私聊", original))
        original.delete()
        val restored = UserMessageFavoriteStore(context)
        val entry = restored.entries.value.single()
        assertEquals(message.content, entry.content)
        assertEquals(message.createdAt, entry.messageAt)
        assertArrayEquals(byteArrayOf(1, 9, 3, 7), restored.audioFile(entry)!!.readBytes())
        assertEquals("role", entry.characterId)
    }

    @Test fun lateAudioIsSavedOnceAndChangingVoicesCannotReplaceTheFavoritePerformance() {
        val context = RuntimeEnvironment.getApplication() as Context
        val store = UserMessageFavoriteStore(context)
        val message = LuluChatMessage(id = "late-voice", conversationId = "group", sender = LuluChatMessage.Sender.Character,
            content = "嗯，我在。", authorCharacterId = "b")
        assertTrue(store.save(message, "b", "乙", null, "群聊"))
        val first = File(context.cacheDir, "first.wav").apply { writeBytes(byteArrayOf(7, 8)) }
        val changed = File(context.cacheDir, "changed.mp3").apply { writeBytes(byteArrayOf(2, 2, 2)) }
        assertTrue(store.retainAudio(message.id, first))
        assertTrue(store.retainAudio(message.id, changed))
        first.delete()
        assertArrayEquals(byteArrayOf(7, 8), store.audioFile(store.entries.value.single())!!.readBytes())
        assertTrue(store.remove(message.id))
        assertTrue(changed.exists())
        assertTrue(UserMessageFavoriteStore(context).entries.value.isEmpty())
    }

    @Test fun cancellingFromFavoritesClearsChatStarWithoutDeletingTheMessage() {
        val context = RuntimeEnvironment.getApplication() as Context
        MigratedDomainStores.initialize(context)
        val role = MigratedDomainStores.characters.create("收藏测试", "角色")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val message = MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "好好睡。", role.characterId)
        assertTrue(MigratedDomainStores.chat.toggleFavorite(message.id))
        assertTrue(UserMessageFavorites.store.contains(message.id))
        assertTrue(MigratedDomainStores.chat.removeUserFavorite(message.id))
        assertFalse(UserMessageFavorites.store.contains(message.id))
        assertFalse(MigratedDomainStores.chat.messages(conversation.id).value.single().favorite)
        assertEquals("好好睡。", MigratedDomainStores.chat.messages(conversation.id).value.single().content)
    }

    @Test fun endingCallRepairsOnlyCurrentPresenceAndRejectsLateOngoingCallState() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "call-state-test"
        CompanionPresenceStore.update(role, "准备看书", "靠着沙发", "还想陪她聊会儿", "开心", "聊天")
        CompanionPresenceStore.beginCall(role)
        assertEquals("通话中", CompanionPresenceStore.current(role)!!.statusText)
        CompanionPresenceStore.selectMessageAnchor(role, Instant.now())
        val old = CompanionPresenceStore.selectedMessageAnchor(role)!!.state
        CompanionPresenceStore.finishCall(role)
        assertEquals("通话已结束", CompanionPresenceStore.current(role)!!.statusText)
        assertEquals("开心", CompanionPresenceStore.current(role)!!.mood)
        assertEquals("还想陪她聊会儿", CompanionPresenceStore.current(role)!!.innerThought)
        assertEquals("通话中", old!!.statusText)
        CompanionPresenceStore.update(role, "正在通话", "正在和她打电话", null, null, "迟到的回复")
        assertEquals("通话已结束", CompanionPresenceStore.current(role)!!.statusText)
        assertEquals("刚放下电话", CompanionPresenceStore.current(role)!!.gesture)
        CompanionPresenceStore.update(role, "想给她打电话", "准备打电话", null, null, "感知")
        assertEquals("想给她打电话", CompanionPresenceStore.current(role)!!.statusText)
    }
}
