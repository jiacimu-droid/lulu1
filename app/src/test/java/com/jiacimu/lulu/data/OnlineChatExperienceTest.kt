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
    @Test fun sixSecondsBeginWithFirstBubbleAndWakeDoesNotShortenOrRestartIt() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "six-second-first-bubble"
        OnlineChatBatchStore.cancel(context, role)
        val first = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_000L)
        val second = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 2_000L)
        val wake = OnlineChatBatchStore.next(context, role, collectMessages = true, now = 2_200L)
        val third = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 3_800L)
        assertEquals(7_000L, first.dueAtMillis)
        assertEquals(7_000L, second.dueAtMillis)
        assertEquals(7_000L, wake.dueAtMillis)
        assertEquals(7_000L, third.dueAtMillis)
        assertEquals(first.revision, third.revision)
        assertFalse(OnlineChatBatchStore.claim(context, role, first.revision, now = 6_999L))
        assertTrue(OnlineChatBatchStore.claim(context, role, first.revision, now = 7_000L))
        assertFalse(OnlineChatBatchStore.claim(context, role, first.revision, now = 7_001L))
        // The user adds messages between two delayed reply bubbles.
        val fourth = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 7_100L)
        val fifth = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 9_300L)
        assertEquals(13_100L, fourth.dueAtMillis)
        assertEquals(13_100L, fifth.dueAtMillis)
        assertEquals(fourth.revision, fifth.revision)
        OnlineChatBatchStore.finish(context, role, first.revision)
        assertFalse(OnlineChatBatchStore.isCurrent(context, role, first.revision))
        assertFalse(OnlineChatBatchStore.claim(context, role, fifth.revision, now = 13_099L))
        assertTrue(OnlineChatBatchStore.claim(context, role, fifth.revision, now = 13_100L))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun independentReflectionCanObserveQuietWindowWithoutClaimingTheMessageBatch() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "parallel-reflection"
        OnlineChatBatchStore.cancel(context, role)
        val batch = OnlineChatBatchStore.next(context, role, collectMessages = true, now = 10_000L)
        assertEquals(16_000L, OnlineChatBatchStore.pendingDueAt(context, role))
        assertFalse(OnlineChatBatchStore.claim(context, role, batch.revision, now = 15_999L))
        assertTrue(OnlineChatBatchStore.claim(context, role, batch.revision, now = 16_000L))
        OnlineChatBatchStore.finish(context, role, batch.revision)
        assertNull(OnlineChatBatchStore.pendingDueAt(context, role))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun wakingBetweenTwoBubblesDoesNotChangeFirstBubbleSixSecondDeadline() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "batch-wake-last"
        OnlineChatBatchStore.cancel(context, role)
        val first = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_000L)
        val wake = OnlineChatBatchStore.next(context, role, true, now = 1_300L)
        val secondBubble = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_500L)
        val unrelated = OnlineChatBatchStore.next(context, role, false, now = 1_900L)
        assertEquals(7_000L, first.dueAtMillis)
        assertEquals(first.dueAtMillis, wake.dueAtMillis)
        assertEquals(7_000L, secondBubble.dueAtMillis)
        assertEquals(7_000L, unrelated.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(context, role, wake.revision, now = 6_999L))
        assertTrue(OnlineChatBatchStore.claim(context, role, wake.revision, now = 7_000L))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun offlineFirstBubbleStartsDeadlineBeforeWakeButtonIsPressed() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create("六秒等待", "耐心读完用户的话")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        OnlineChatBatchStore.cancel(context, role.characterId)
        val firstMessage = MigratedDomainStores.chat.sendUserMessage(conversation.id, "第一句话，等等我")
        val firstDeadline = firstMessage.createdAt.toEpochMilli() + OnlineChatBatchStore.QUIET_MILLIS
        val wake = OnlineChatBatchStore.next(context, role.characterId, true,
            now = firstMessage.createdAt.toEpochMilli() + 500L)
        assertEquals(firstDeadline, wake.dueAtMillis)
        val more = MigratedDomainStores.chat.sendUserMessage(conversation.id, "第二句话")
        val scheduled = OnlineChatBatchStore.next(context, role.characterId, true, now = more.createdAt.toEpochMilli())
        assertEquals(firstDeadline, scheduled.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(context, role.characterId, scheduled.revision,
            now = scheduled.dueAtMillis - 1L))
        OnlineChatBatchStore.cancel(context, role.characterId)
    }

    @Test fun autonomousImmediateBatchCannotMakeFirstChatMessageReadImmediately() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "batch-no-shortcut"
        OnlineChatBatchStore.cancel(context, role)
        val background = OnlineChatBatchStore.next(context, role, collectMessages = false, now = 1_000L)
        assertEquals(1_000L, background.dueAtMillis)
        val firstBubble = OnlineChatBatchStore.next(context, role, collectMessages = true, now = 1_100L)
        val wake = OnlineChatBatchStore.next(context, role, collectMessages = true, now = 1_500L)
        assertEquals(7_100L, firstBubble.dueAtMillis)
        assertEquals(firstBubble.dueAtMillis, wake.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(context, role, background.revision, now = 1_100L))
        assertFalse(OnlineChatBatchStore.claim(context, role, wake.revision, now = 7_099L))
        assertTrue(OnlineChatBatchStore.claim(context, role, wake.revision, now = 7_100L))
        OnlineChatBatchStore.finish(context, role, wake.revision)
        assertNull(OnlineChatBatchStore.dueAt(context, role, wake.revision))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun noUnreadDoesNotCallModelAndStillConsumesItsWakeWindow() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "empty-batch"
        val batch = OnlineChatBatchStore.next(context, role, true, now = 0L)
        assertEquals(0, ProactivePerceptionRuntime.runDueCycle(context, "在线输入", role, force = true,
            onlineRevision = batch.revision, requiresUnread = true))
        assertFalse(OnlineChatBatchStore.claim(context, role, batch.revision))
    }

    @Test fun interruptedReadingRetainsDeadlineAndCompletingOldReplyKeepsNewMessagesPending() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "resume-reading"
        val first = OnlineChatBatchStore.next(context, role, true, now = 100L)
        assertTrue(OnlineChatBatchStore.claim(context, role, first.revision))
        OnlineChatBatchStore.finish(context, role, first.revision, completed = false)
        assertEquals(6_100L, OnlineChatBatchStore.dueAt(context, role, first.revision))
        assertTrue(OnlineChatBatchStore.claim(context, role, first.revision))
        val next = OnlineChatBatchStore.next(context, role, true, now = 4_000L)
        OnlineChatBatchStore.finish(context, role, first.revision)
        assertEquals(10_000L, OnlineChatBatchStore.dueAt(context, role, next.revision))
        assertTrue(OnlineChatBatchStore.claim(context, role, next.revision))
        OnlineChatBatchStore.finish(context, role, next.revision)
        assertNull(OnlineChatBatchStore.dueAt(context, role, next.revision))
    }

    @Test fun interleavedUserBubbleRemainsUnreadAfterEarlierReplyWasDelivered() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        // Robolectric disables AndroidX Startup; online message sends schedule a real worker.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        val character = MigratedDomainStores.characters.create("多气泡不漏读", "自然语速")
        val conversation = MigratedDomainStores.chat.ensureConversation(character.characterId, character.displayName)
        CompanionOnlineStore.wakeCharacter(character.characterId, CompanionOnlineReason.PrivateWake,
            "查看多气泡", perceiveNow = false)
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "第一个气泡")
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "第二个气泡")
        val readBeforeModel = CompanionOnlineStore.unreadChatSnapshot(character.characterId)
        assertTrue(readBeforeModel.text.contains("第一个气泡"))
        assertTrue(readBeforeModel.text.contains("第二个气泡"))
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "角色第一条回复", character.characterId)
        // Exactly while the existing answer is being delivered, the user sends another bubble.
        Thread.sleep(10)
        val interleaved = MigratedDomainStores.chat.sendUserMessage(conversation.id, "第三个插队气泡")
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id, "角色第二条回复", character.characterId)
        CompanionOnlineStore.markSeen(character.characterId, readBeforeModel)
        val next = CompanionOnlineStore.unreadChatSnapshot(character.characterId)
        assertTrue(next.text.contains("第三个插队气泡"))
        assertTrue(next.newestIds.contains(interleaved.id))
        assertFalse(next.text.contains("第一个气泡"))
    }

    @Test fun timestampCollisionDoesNotLoseMessageIdsNotInEarlierSnapshot() {
        val instant = java.time.Instant.parse("2026-10-09T12:00:00Z")
        assertFalse(CompanionOnlineStore.isUnreadAtCursor(
            "old", instant, instant, instant, setOf("old")))
        assertTrue(CompanionOnlineStore.isUnreadAtCursor(
            "third", instant, instant, instant, setOf("old", "second")))
        assertTrue(CompanionOnlineStore.isUnreadAtCursor(
            "later", instant.plusMillis(1), instant, instant, setOf("old")))
        assertFalse(CompanionOnlineStore.isUnreadAtCursor(
            "before", instant.minusMillis(1), instant, instant, emptySet()))
    }

    @Test fun speechFingerprintsStayRoleSpecificAndCrossWorldScenesExcludeOriginalConversation() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CharacterLifeStore.initialize(context)
        val roleA = MigratedDomainStores.characters.create("说话有个性甲", "正常人设")
        val roleB = MigratedDomainStores.characters.create("说话有个性乙", "另一种人设")
        CharacterLifeStore.setProfile(roleA.characterId, "speechHabits", "紧张时用……拖长停顿")
        CharacterLifeStore.setProfile(roleB.characterId, "speechHabits", "爱说倒装句")
        val chatA = MigratedDomainStores.chat.ensureConversation(roleA.characterId, roleA.displayName)
        MigratedDomainStores.chat.appendCharacterMessage(chatA.id, "我、我先说一个事情……", roleA.characterId)
        val fullA = CharacterSpeechIdentity.promptSection(roleA.characterId)
        assertTrue(fullA.contains("紧张时用……拖长停顿"))
        assertTrue(fullA.contains("我、我先说一个事情"))
        assertFalse(fullA.contains("爱说倒装句"))
        val fullB = CharacterSpeechIdentity.promptSection(roleB.characterId)
        assertFalse(fullB.contains("我、我先说一个事情"))
        assertTrue(fullB.contains("爱说倒装句"))
        val isolatedA = CharacterSpeechIdentity.promptSection(roleA.characterId, includeObserved = false)
        assertTrue(isolatedA.contains("紧张时用……拖长停顿"))
        assertFalse(isolatedA.contains("我、我先说一个事情"))
        val groupStyle = CharacterSpeechIdentity.promptSection(roleA.characterId,
            includeObserved = true, includePersonalSamples = false)
        assertTrue(groupStyle.contains("紧张时用……拖长停顿"))
        assertFalse(groupStyle.contains("我、我先说一个事情"))
    }

    @Test fun actualActivityRefreshesFiveMinutesWithoutStackingTime() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val start = java.time.Instant.now()
        val role = "activity-refresh"
        CompanionOnlineStore.recordActivity(role, start)
        assertEquals(start.plusSeconds(300), CompanionOnlineStore.states.value.getValue(role).onlineUntil)
        CompanionOnlineStore.recordActivity(role, start.plusSeconds(290))
        assertEquals(start.plusSeconds(590), CompanionOnlineStore.states.value.getValue(role).onlineUntil)
        CompanionOnlineStore.recordActivity(role, start.plusSeconds(600))
        assertEquals(start.plusSeconds(900), CompanionOnlineStore.states.value.getValue(role).onlineUntil)
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
        assertEquals(4, upgraded.getInt("jiangDuPresetVersion"))
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

    @Test fun longDefaultProfileIsReorganizedOnceWithoutChangingMemoriesOrEditedFields() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("江渡", LegacyJiangDuProfileSchema.jiangDuPersona +
            "\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + LegacyJiangDuProfileSchema.jiangDuRespect)
        CharacterIdentityStore.set(role.characterId, LegacyJiangDuProfileSchema.jiangDuIdentity)
        DigitalLifeProfileStore.confirmLegacyLifeForm(role.characterId, role.displayName, "创造者", CharacterLifeForm.DIGITAL)
        val profile = JSONObject().apply { LegacyJiangDuProfileSchema.jiangDu.forEach { (key, value) -> put(key, value) } }
            .put("interests", "天文").put("expression", "我自己改的口语节奏")
        val old = JSONObject().put("jiangDuPresetVersion", 3).put("profile", profile)
            .put("intention", JSONObject().put("aim", "继续看书"))
        context.getSharedPreferences("lulu_character_life", 0).edit().putString(role.characterId, old.toString()).commit()
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(CharacterLifeStore, null)
        CharacterLifeStore.initialize(context)
        val upgraded = CharacterLifeStore.state(role.characterId)
        assertEquals(CharacterProfileSchema.jiangDuPersona, MigratedDomainStores.characters.get(role.characterId).persona)
        assertEquals(CharacterProfileSchema.jiangDuIdentity, CharacterIdentityStore.identities.value[role.characterId])
        assertEquals(CharacterProfileSchema.jiangDu.getValue("values"), upgraded.getJSONObject("profile").getString("values"))
        assertEquals("我自己改的口语节奏", upgraded.getJSONObject("profile").getString("expression"))
        assertEquals("天文", upgraded.getJSONObject("profile").getString("interests"))
        assertEquals("继续看书", upgraded.getJSONObject("intention").getString("aim"))
        assertEquals(old.getJSONObject("profile").toString(), upgraded.getJSONObject("jiangDuOrganizationBackup").getJSONObject("profile").toString())
    }

    @Test fun actualCallSpeechIsRememberedButNeverBecomesAnotherUnreadTextRequest() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("通话角色", "自然口语")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        CompanionOnlineStore.initialize(context)
        MigratedDomainStores.chat.appendVoiceMessage(conversation.id, "voice-test-user", "电话里刚说的话", false)
        assertFalse(CompanionOnlineStore.unreadChatSnapshot(role.characterId).text.contains("电话里刚说的话"))
        assertTrue(CompanionOnlineStore.isOnline(role.characterId))
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.any { it.id == "voice-test-user" })
        assertTrue(SharedExperienceTimeline.all(role.characterId).any { it.channel.contains("电话") })
        // Config.NONE deliberately disables AndroidX Startup; initialize the real scheduler fixture.
        if (runCatching { androidx.work.WorkManager.getInstance(context) }.isFailure) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "另外发来的聊天消息")
        val unread = CompanionOnlineStore.unreadChatSnapshot(role.characterId)
        assertTrue(unread.text.contains("另外发来的聊天消息"))
        assertTrue(conversation.id in unread.conversationIds)
    }

    private fun initializeStores(context: Context) {
        // Each Robolectric test owns fresh preferences; the singleton must use this app's context.
        CompanionOnlineStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(CompanionOnlineStore, null)
        CompanionOnlineStore.javaClass.getDeclaredField("appContext").apply { isAccessible = true }.set(CompanionOnlineStore, null)
        LuluRepositories.initialize(context)
        SharedExperienceTimeline.initialize(context)
        MigratedDomainStores.initialize(context)
        CharacterIdentityStore.initialize(context)
        DigitalLifeProfileStore.initialize(context)
    }
}
