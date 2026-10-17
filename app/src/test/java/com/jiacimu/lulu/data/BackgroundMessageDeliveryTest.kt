package com.jiacimu.lulu.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class BackgroundMessageDeliveryTest {
    private lateinit var context: Context
    private lateinit var conversation: LuluConversation
    private lateinit var role: CharacterSettings
    private lateinit var manager: NotificationManager

    @Before fun before() {
        reset()
        context = RuntimeEnvironment.getApplication()
        manager = context.getSystemService(NotificationManager::class.java)
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        UserInteractionPresenceStore.initialize(context)
        CompanionPresenceStore.initialize(context)
        CharacterInnerLifeStore.initialize(context)
        CharacterLifeStore.initialize(context)
        role = MigratedDomainStores.characters.create("后台测试角色")
        conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        manager.cancelAll()
    }

    @After fun after() { reset() }

    @Test fun genericToolMessageFromColdBackgroundIsDurableAndPostsOneHighPriorityNotification() = runBlocking {
        assertNull(UserInteractionPresenceStore.state.value.appVisible)
        val result = JSONObject(ToolRouter.execute(context, role.characterId, "send_private_message",
            JSONObject().put("text", "刚看完，想听听你的看法。"), requestId = "background-message"))
        assertTrue(result.toString(), result.getBoolean("success"))
        val notification = notifications().single()
        assertEquals(Notification.CATEGORY_MESSAGE, notification.category)
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(notification.channelId).importance)
        assertEquals(role.displayName, notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("刚看完，想听听你的看法。", notification.extras.getString(Notification.EXTRA_TEXT))
        val restored = InMemoryLuluChatStore().apply { initialize(context) }
        assertEquals("刚看完，想听听你的看法。", restored.messages(conversation.id).value.last().content)
        assertEquals(1, restored.conversations.value.first { it.id == conversation.id }.unreadCount)
        ToolRouter.execute(context, role.characterId, "send_private_message",
            JSONObject().put("text", "刚看完，想听听你的看法。"), requestId = "background-message")
        assertEquals(1, notifications().size)
        assertEquals(1, MigratedDomainStores.chat.messages(conversation.id).value.size)
    }

    @Test fun currentVisibleChatAndPhoneTranscriptsDoNotCreateMessagePopups() {
        UserInteractionPresenceStore.setAppVisible(true)
        UserInteractionPresenceStore.setConversation(conversation.id)
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "我在。", role.characterId)
        assertTrue(notifications().isEmpty())
        assertEquals(0, MigratedDomainStores.chat.conversations.value.first { it.id == conversation.id }.unreadCount)
        UserInteractionPresenceStore.setAppVisible(false)
        MigratedDomainStores.chat.appendVoiceMessage(conversation.id, "voice-test", "电话里的话", true)
        MigratedDomainStores.chat.appendSystemMessage(conversation.id, "阅读记录")
        assertTrue(notifications().isEmpty())
    }

    @Test fun replyBubblesUpdateOneNotificationWithoutRepeatedSoundAndNextTurnCanAlert() {
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "第一句", role.characterId, replyBatchId = "turn1")
        assertEquals(0, notifications().single().flags and Notification.FLAG_ONLY_ALERT_ONCE)
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "第二句", role.characterId, replyBatchId = "turn1")
        assertTrue(notifications().single().flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "[游戏邀约|memory_match|翻牌] 过一会的新话题", role.characterId, replyBatchId = "turn2")
        assertEquals(0, notifications().single().flags and Notification.FLAG_ONLY_ALERT_ONCE)
        assertEquals("过一会的新话题", notifications().single().extras.getString(Notification.EXTRA_TEXT))
        MigratedDomainStores.chat.markConversationRead(conversation.id)
        assertTrue(notifications().isEmpty())
    }

    @Test fun mutedGroupDoesNotPopupButUnmutedGroupUsesItsActualConversation() {
        val group = MigratedDomainStores.chat.createGroupConversation("朋友们", listOf(role.characterId))
        MigratedDomainStores.chat.updateGroupConversation(group.id, group.groupChat!!.copy(muted = true))
        MigratedDomainStores.chat.appendCharacterMessage(group.id, "静音消息", role.characterId)
        assertTrue(notifications().isEmpty())
        MigratedDomainStores.chat.updateGroupConversation(group.id, group.groupChat!!.copy(muted = false))
        MigratedDomainStores.chat.appendCharacterMessage(group.id, "新的群消息", role.characterId)
        assertEquals("${role.displayName} · 朋友们", notifications().single().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun disabledLegacyChannelIsRespectedInsteadOfBypassedByAnUpgrade() {
        manager.createNotificationChannel(NotificationChannel("lulu_proactive_messages", "旧消息", NotificationManager.IMPORTANCE_NONE))
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "已保存，但不越过通知关闭设置", role.characterId)
        assertTrue(notifications().isEmpty())
        assertNull(manager.getNotificationChannel("lulu_companion_messages_v2"))
        assertEquals(1, MigratedDomainStores.chat.messages(conversation.id).value.size)
    }

    @Test fun oldAppDefaultCanUpgradeToHeadsUpWithoutMutatingTheImmutableChannel() {
        manager.createNotificationChannel(NotificationChannel("lulu_proactive_messages", "旧消息", NotificationManager.IMPORTANCE_DEFAULT))
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "有新消息", role.characterId)
        assertEquals("lulu_companion_messages_v2", notifications().single().channelId)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("lulu_proactive_messages").importance)
    }

    private fun notifications() = shadowOf(manager).allNotifications.filter { it.category == Notification.CATEGORY_MESSAGE }

    private fun reset() {
        MemoryTestEnvironment.release()
        listOf(MigratedDomainStores.chat, MigratedDomainStores.characters, UserInteractionPresenceStore,
            CompanionPresenceStore, CharacterInnerLifeStore, CharacterLifeStore, CompanionOnlineStore).forEach { target ->
            target.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(target, null)
        }
        CompanionOnlineStore.javaClass.getDeclaredField("appContext").apply { isAccessible = true }.set(CompanionOnlineStore, null)
    }
}
