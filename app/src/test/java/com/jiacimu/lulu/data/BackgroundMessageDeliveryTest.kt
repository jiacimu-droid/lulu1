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
        CharacterMessageNotifications.dismissInApp()
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

    @After fun after() {
        CharacterMessageNotifications.dismissInApp()
        reset()
    }

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

    @Test fun manuallyRequestedHeadsUpTestUsesRealMessageChannelWithoutForgingChat() {
        assertTrue(CharacterMessageNotifications.sendHeadsUpTest(context))
        val notification = notifications().single()
        assertEquals("露露机 · 横幅测试",
            notification.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("这是一条测试提醒，不是角色发来的消息",
            notification.extras.getString(Notification.EXTRA_TEXT))
        assertEquals(NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel(notification.channelId).importance)
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.isEmpty())
        assertNull(CharacterMessageNotifications.inAppBanner.value)
    }

    @Test fun explicitQuietChannelAlsoDisablesManualHeadsUpTest() {
        manager.createNotificationChannel(NotificationChannel(
            "lulu_proactive_messages", "旧消息静音", NotificationManager.IMPORTANCE_LOW))
        assertFalse(CharacterMessageNotifications.sendHeadsUpTest(context))
        assertTrue(notifications().isEmpty())
        assertNull(manager.getNotificationChannel("lulu_companion_messages_v2"))
    }

    @Test fun otherVisibleAppPageShowsRealMessageBannerAndClickTarget() {
        UserInteractionPresenceStore.setAppVisible(true)
        UserInteractionPresenceStore.setConversation(null)
        val first = MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "吃饭啦，快看看我的消息", role.characterId)
        val banner = CharacterMessageNotifications.inAppBanner.value
        assertNotNull(banner)
        assertEquals(first.id, banner!!.messageId)
        assertEquals(conversation.id, banner.conversationId)
        assertEquals(role.displayName, banner.title)
        assertEquals("吃饭啦，快看看我的消息", banner.text)
        assertEquals(1, notifications().size)

        val newer = MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "我又找到一个好玩的地方", role.characterId)
        CharacterMessageNotifications.dismissInApp(first.id)
        assertEquals(newer.id, CharacterMessageNotifications.inAppBanner.value?.messageId)
        MigratedDomainStores.chat.markConversationRead(conversation.id)
        assertNull(CharacterMessageNotifications.inAppBanner.value)
        assertTrue(notifications().isEmpty())
    }

    @Test fun backgroundAndMutedGroupNeverShowForegroundBanner() {
        MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "后台保留系统通知", role.characterId)
        assertNull(CharacterMessageNotifications.inAppBanner.value)
        assertEquals(1, notifications().size)
        UserInteractionPresenceStore.setAppVisible(true)
        val friend = MigratedDomainStores.characters.create("群聊朋友")
        val group = MigratedDomainStores.chat.createGroupConversation(
            "晚间聊天", listOf(role.characterId, friend.characterId))
        MigratedDomainStores.chat.updateGroupConversation(
            group.id, group.groupChat!!.copy(muted = true))
        MigratedDomainStores.chat.appendCharacterMessage(
            group.id, "静音群不弹窗", role.characterId)
        assertNull(CharacterMessageNotifications.inAppBanner.value)
        MigratedDomainStores.chat.updateGroupConversation(
            group.id, group.groupChat!!.copy(muted = false))
        MigratedDomainStores.chat.appendCharacterMessage(
            group.id, "有人找你", role.characterId)
        assertEquals("${role.displayName} · 晚间聊天",
            CharacterMessageNotifications.inAppBanner.value?.title)
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
        val friend = MigratedDomainStores.characters.create("群聊测试朋友")
        val group = MigratedDomainStores.chat.createGroupConversation("朋友们", listOf(role.characterId, friend.characterId))
        MigratedDomainStores.chat.updateGroupConversation(group.id, group.groupChat!!.copy(muted = true))
        MigratedDomainStores.chat.appendCharacterMessage(group.id, "静音消息", role.characterId)
        assertTrue(notifications().isEmpty())
        MigratedDomainStores.chat.updateGroupConversation(group.id, group.groupChat!!.copy(muted = false))
        MigratedDomainStores.chat.appendCharacterMessage(group.id, "新的群消息", role.characterId)
        assertEquals("${role.displayName} · 朋友们", notifications().single().extras.getString(Notification.EXTRA_TITLE))
    }

    @Test fun explicitlyQuietLegacyChannelDoesNotTriggerForegroundHeadsUp() {
        manager.createNotificationChannel(NotificationChannel(
            "lulu_proactive_messages", "旧消息静音", NotificationManager.IMPORTANCE_LOW))
        UserInteractionPresenceStore.setAppVisible(true)
        UserInteractionPresenceStore.setConversation(null)
        MigratedDomainStores.chat.appendCharacterMessage(
            conversation.id, "静音渠道保留信息但不弹窗", role.characterId)
        assertEquals("lulu_proactive_messages", notifications().single().channelId)
        assertNull(CharacterMessageNotifications.inAppBanner.value)
    }

    @Test fun disabledLegacyChannelIsRespectedInsteadOfBypassedByAnUpgrade() {
        manager.createNotificationChannel(NotificationChannel("lulu_proactive_messages", "旧消息", NotificationManager.IMPORTANCE_NONE))
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "已保存，但不越过通知关闭设置", role.characterId)
        assertTrue(notifications().isEmpty())
        assertNull(CharacterMessageNotifications.inAppBanner.value)
        assertNull(manager.getNotificationChannel("lulu_companion_messages_v2"))
        assertEquals(1, MigratedDomainStores.chat.messages(conversation.id).value.size)
    }

    @Test fun legacySoundChoiceDoesNotDisableHeadsUpButQuietChoicesRemainProtected() {
        val policy = CharacterMessageNotifications::preserveLegacyChannel
        assertFalse(policy(NotificationManager.IMPORTANCE_DEFAULT,
            false, true, true)) // Only the ringtone was changed; preserve it on HIGH.
        assertTrue(policy(NotificationManager.IMPORTANCE_DEFAULT,
            false, true, false)) // Explicitly silent sound must not be overridden.
        assertTrue(policy(NotificationManager.IMPORTANCE_DEFAULT,
            true, false, true)) // Explicit notification importance is authoritative.
        assertTrue(policy(NotificationManager.IMPORTANCE_LOW,
            false, false, true)) // Quiet channels are never bypassed.
        assertFalse(policy(NotificationManager.IMPORTANCE_DEFAULT,
            false, false, true))
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
