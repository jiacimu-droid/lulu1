package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.LuluRepositories
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OnlineChatExperienceTest {
    @Test fun fourMessageEventsProduceOneCurrentBatchAndEmptyQueueDoesNotCallModel() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "batch-four"
        val batches = listOf(0L, 3_000L, 7_000L, 10_000L).map { at ->
            OnlineChatBatchStore.next(context, role, collectMessages = true, now = at)
        }
        assertEquals(16_000L, batches.last().dueAtMillis)
        assertEquals(1, batches.count { OnlineChatBatchStore.isCurrent(context, role, it.revision) })
        val disk = context.getSharedPreferences("lulu_online_chat_batches", 0)
        assertEquals(batches.last().revision, disk.getLong("revision:$role", 0))
        assertEquals(0, ProactivePerceptionRuntime.runDueCycle(context, "在线输入", role, force = true,
            onlineRevision = batches.first().revision, requiresUnread = true))
        assertEquals(0, ProactivePerceptionRuntime.runDueCycle(context, "在线输入", role, force = true,
            onlineRevision = batches.last().revision, requiresUnread = true))
        val other = OnlineChatBatchStore.next(context, "another-role", true, now = 0)
        OnlineChatBatchStore.cancel(context, role)
        assertFalse(OnlineChatBatchStore.isCurrent(context, role, batches.last().revision))
        assertTrue(OnlineChatBatchStore.isCurrent(context, "another-role", other.revision))
    }

    @Test fun realBackgroundAndDeliveryLeasesStayVisibleUntilBothFinishAndFailuresClearThem() = runBlocking {
        val old = ChatGenerationActivity.begin("typing-role", setOf("typing-conversation", "typing-group"))
        val delivery = ChatGenerationActivity.begin("typing-role", setOf("typing-conversation"))
        assertTrue(ChatGenerationActivity.isRunning("typing-conversation"))
        assertTrue(ChatGenerationActivity.isRunning("typing-group"))
        ChatGenerationActivity.end(old)
        assertTrue(ChatGenerationActivity.isRunning("typing-conversation"))
        assertFalse(ChatGenerationActivity.isRunning("typing-group"))
        ChatGenerationActivity.clearCharacter("typing-role")
        val newer = ChatGenerationActivity.begin("typing-role", setOf("typing-conversation"))
        ChatGenerationActivity.end(delivery)
        assertTrue(ChatGenerationActivity.isRunning("typing-conversation"))
        ChatGenerationActivity.end(newer)
        try {
            ChatGenerationActivity.during("typing-role", setOf("typing-conversation")) {
                assertTrue(ChatGenerationActivity.isRunning("typing-conversation"))
                error("API failure")
            }
        } catch (_: IllegalStateException) {}
        assertFalse(ChatGenerationActivity.isRunning("typing-conversation"))
    }

    @Test fun respectUpgradePreservesCustomIdentityPersonaInterestsAndLaterUserChanges() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("江渡", "用户自定义的人设")
        CharacterIdentityStore.set(role.characterId, "用户自定义身份")
        DigitalLifeProfileStore.confirmLegacyLifeForm(role.characterId, role.displayName, "创造者", CharacterLifeForm.DIGITAL)
        val old = JSONObject().put("jiangDuPresetVersion", 2)
            .put("profile", JSONObject().put("care", "用户自己的关心方式").put("interests", "天文"))
            .put("intention", JSONObject().put("aim", "继续看书"))
        context.getSharedPreferences("lulu_character_life", 0).edit().putString(role.characterId, old.toString()).commit()
        // Simulate a restart with the previous installed schema.
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(CharacterLifeStore, null)
        CharacterLifeStore.initialize(context)
        val upgraded = CharacterLifeStore.state(role.characterId)
        assertEquals(3, upgraded.getInt("jiangDuPresetVersion"))
        assertEquals("用户自定义身份", CharacterIdentityStore.identities.value[role.characterId])
        assertTrue(MigratedDomainStores.characters.get(role.characterId).persona.startsWith("用户自定义的人设"))
        assertEquals("用户自己的关心方式", upgraded.getJSONObject("profile").getString("care"))
        assertEquals("天文", upgraded.getJSONObject("profile").getString("interests"))
        assertEquals("继续看书", upgraded.getJSONObject("intention").getString("aim"))
        assertEquals("用户自定义的人设", upgraded.getJSONObject("jiangDuRespectBackup").getString("persona"))
        assertTrue(CharacterRuntime.definition(role.characterId).promptSection().contains("心声、心情"))
        CharacterLifeStore.setProfile(role.characterId, "respect", "我后来自己调整的相处方式")
        MigratedDomainStores.characters.update(MigratedDomainStores.characters.get(role.characterId).copy(persona = "后来自己改的人设"))
        CharacterLifeStore.applyJiangDuPreset(role.characterId)
        assertEquals("后来自己改的人设", MigratedDomainStores.characters.get(role.characterId).persona)
        assertEquals("我后来自己调整的相处方式", CharacterLifeStore.state(role.characterId).getJSONObject("profile").getString("respect"))
    }

    @Test fun actualCallSpeechIsRememberedButNeverBecomesAnotherUnreadTextRequest() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("通话角色", "自然口语")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        CompanionOnlineStore.initialize(context)
        MigratedDomainStores.chat.appendVoiceMessage(conversation.id, "voice-test-user", "电话里刚说的话", false)
        assertFalse(CompanionOnlineStore.unreadChatSnapshot(role.characterId).text.contains("电话里刚说的话"))
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.any { it.id == "voice-test-user" })
        assertTrue(SharedExperienceTimeline.all(role.characterId).any { it.channel.contains("电话") })
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "另外发来的聊天消息")
        val unread = CompanionOnlineStore.unreadChatSnapshot(role.characterId)
        assertTrue(unread.text.contains("另外发来的聊天消息"))
        assertTrue(conversation.id in unread.conversationIds)
    }

    private fun initializeStores(context: Context) {
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
    }
}
