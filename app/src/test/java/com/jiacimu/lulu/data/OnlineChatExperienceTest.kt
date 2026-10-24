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
    @Test fun roleCanGoOfflineImmediatelyWithoutErasingItsNextWake() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val characterId = MigratedDomainStores.characters.create("即时休眠工具测试").characterId
        val chat = MigratedDomainStores.chat.ensureConversation(characterId, "即时休眠工具测试")
        CompanionOnlineStore.wakeCharacter(characterId, CompanionOnlineReason.NewActivity,
            "角色进入聊天", perceiveNow = false)
        assertTrue(CompanionOnlineStore.isOnline(characterId))
        assertNotNull(CapabilityRegistry.find("go_offline"))

        val step = org.json.JSONArray()
            .put(JSONObject().put("tool", "go_offline")
                .put("args", JSONObject()).put("requiresPreviousSuccess", true))
        val result = OrderedCharacterActions.execute(context, characterId, step,
            requestId = "sleep-after-real-reply", initialCompleted = true)
        assertTrue(result.notes.toString(), result.anyAccepted)
        assertEquals(CompanionOnlineReason.SelfChosenSleep,
            CompanionOnlineStore.states.value.getValue(characterId).reason)
        assertFalse(CompanionOnlineStore.isOnline(characterId))

        // Idempotent replay cannot restart the five-minute window.
        val replay = OrderedCharacterActions.execute(context, characterId, step,
            requestId = "sleep-after-real-reply", initialCompleted = true)
        assertTrue(replay.anyAccepted)
        assertFalse(CompanionOnlineStore.isOnline(characterId))

        // An actual later message remains a valid reason to wake up again.
        MigratedDomainStores.chat.sendUserMessage(chat.id, "宝宝，回来一下")
        assertTrue(CompanionOnlineStore.isOnline(characterId))
        OnlineChatBatchStore.cancel(context, characterId)
    }

    @Test fun failedPreviousStepDoesNotExecuteDependentSleep() = runBlocking {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val characterId = MigratedDomainStores.characters.create("顺序依赖测试").characterId
        CompanionOnlineStore.wakeCharacter(characterId, CompanionOnlineReason.NewActivity,
            "开始行动", perceiveNow = false)
        val plan = org.json.JSONArray()
            .put(JSONObject().put("tool", "go_offline")
                .put("args", JSONObject()).put("requiresPreviousSuccess", true))
        val skipped = OrderedCharacterActions.execute(context, characterId, plan,
            requestId = "reply-not-delivered", initialCompleted = false)
        assertFalse(skipped.anyAccepted)
        assertTrue(skipped.notes.first().contains("前一步尚未成功"))
        assertTrue(CompanionOnlineStore.isOnline(characterId))
    }

    @Test fun aNewPrivateMessageWakesAnOfflineCharacterWithoutWaitingFiveMinutes() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create(
            "离线新消息主动感知测试", "即使离线也接得住用户的新消息",
        )
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        OnlineChatBatchStore.cancel(context, role.characterId)
        CompanionOnlineStore.resetCharacter(role.characterId)
        assertFalse(CompanionOnlineStore.isOnline(role.characterId))

        val sent = MigratedDomainStores.chat.sendUserMessage(conversation.id, "现在在吗？")
        val state = CompanionOnlineStore.states.value.getValue(role.characterId)
        assertTrue(state.onlineUntil.isAfter(sent.createdAt.plusSeconds(290)))
        assertEquals(CompanionOnlineReason.NewActivity, state.reason)
        val due = OnlineChatBatchStore.pendingDueAt(context, role.characterId)
        assertNotNull(due)
        assertEquals(sent.createdAt.toEpochMilli() + OnlineChatBatchStore.QUIET_MILLIS, due)
        assertTrue(CompanionOnlineStore.unreadChatSnapshot(role.characterId).text.contains("现在在吗"))
        OnlineChatBatchStore.cancel(context, role.characterId)
    }

    @Test fun photoFollowedByLaterTextMustRemainInTheSameVisionInputBatch() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create(
            "多消息识图回归", "收到图片再收到文字也要真正看图",
        )
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        OnlineChatBatchStore.cancel(context, role.characterId)
        CompanionOnlineStore.resetCharacter(role.characterId)
        val source = java.io.File.createTempFile("photo-before-message-", ".png", context.cacheDir)
        val bitmap = android.graphics.Bitmap.createBitmap(
            32, 24, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(source).use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
        val stable = com.jiacimu.lulu.QqChatPhotoAttachmentStore.import(
            context, android.net.Uri.fromFile(source))
        source.delete()
        val photo = MigratedDomainStores.chat.sendUserMessage(chat.id,
            com.jiacimu.lulu.encodeQqChatImage(stable, "宝宝你看"))
        // This later bubble has a newer timestamp, but must never replace the
        // image evidence attached to the SAME unread chat turn.
        val words = MigratedDomainStores.chat.sendUserMessage(chat.id,
            "别人对我声音的评价")
        val unread = CompanionOnlineStore.unreadChatSnapshot(role.characterId)
        assertTrue(unread.text.contains("别人对我声音的评价"))
        assertTrue(photo.id in unread.imageEvidenceIds)
        assertTrue(words.id !in unread.imageEvidenceIds)
        assertTrue(photo.id !in unread.newestIds || photo.createdAt == words.createdAt)
        val pixels = runBlocking {
            com.jiacimu.lulu.QqChatImageModelInput.prepare(
                context, role.characterId, unread.imageEvidenceIds)
        }
        assertEquals(1, pixels.count)
        assertEquals(0, pixels.failures)
        assertEquals(1, pixels.urls.size)
        assertTrue(pixels.urls.first().startsWith("data:image/jpeg;base64,"))
        assertTrue(com.jiacimu.lulu.QqChatImageModelInput.lastReadStatus(
            context, role.characterId).contains("1/1 张"))
        assertEquals("这个角色尚无图片读取记录",
            com.jiacimu.lulu.QqChatImageModelInput.lastReadStatus(
                context, role.characterId + "-unrelated"))
        OnlineChatBatchStore.cancel(context, role.characterId)
    }

    @Test fun groupUserMessageWakesAllParticipantsIndependently() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val a = MigratedDomainStores.characters.create("群成员甲感知", "独立判断").characterId
        val b = MigratedDomainStores.characters.create("群成员乙感知", "独立判断").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("来消息就醒的群聊", listOf(a, b))
        for (id in listOf(a, b)) {
            OnlineChatBatchStore.cancel(context, id)
            CompanionOnlineStore.resetCharacter(id)
            assertFalse(CompanionOnlineStore.isOnline(id))
        }
        val incoming = MigratedDomainStores.chat.sendUserMessage(group.id, "你们都在吗？")
        for (id in listOf(a, b)) {
            assertTrue(CompanionOnlineStore.isOnline(id))
            assertEquals(CompanionOnlineReason.NewActivity,
                CompanionOnlineStore.states.value.getValue(id).reason)
            assertTrue(CompanionOnlineStore.unreadChatSnapshot(id).text.contains("你们都在吗"))
            assertEquals(incoming.createdAt.toEpochMilli() + OnlineChatBatchStore.QUIET_MILLIS,
                OnlineChatBatchStore.pendingDueAt(context, id))
            OnlineChatBatchStore.cancel(context, id)
        }
    }

    @Test fun alreadyOnlineRoleTreatsEachNewUserMessageAsItsOwnEvent() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create(
            "已在线消息感知回归", "有新消息就进行一次独立感知",
        ).characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "在线消息窗口")
        OnlineChatBatchStore.cancel(context, role)
        val sessionStart = java.time.Instant.now()
        CompanionOnlineStore.wakeCharacter(
            role, CompanionOnlineReason.PrivateWake,
            "主动上线测试", perceiveNow = false, now = sessionStart,
        )
        val previousUntil = CompanionOnlineStore.states.value.getValue(role).onlineUntil
        val incoming = MigratedDomainStores.chat.sendUserMessage(conversation.id, "你现在在做什么？")
        val deadline = OnlineChatBatchStore.pendingDueAt(context, role)
        assertNotNull(deadline)
        assertEquals(incoming.createdAt.toEpochMilli() + OnlineChatBatchStore.QUIET_MILLIS, deadline)
        assertTrue(deadline!! < previousUntil.toEpochMilli())
        assertTrue(CompanionOnlineStore.unreadChatSnapshot(role).text.contains("你现在在做什么"))
        // Online lease expiry is not a prerequisite for receiving the new turn.
        assertTrue(CompanionOnlineStore.isOnline(role))
        assertTrue(CompanionOnlineStore.states.value.getValue(role).onlineUntil
            .isAfter(incoming.createdAt))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun aDeliveredCharacterMessageDoesNotAutomaticallyBuyFiveMoreMinutes() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val character = MigratedDomainStores.characters.create(
            "正常回复不能自动续醒", "可以根据真实意愿决定何时休息",
        )
        val start = java.time.Instant.now()
        val role = character.characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(
            role, character.displayName,
        )
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.PrivateWake,
            trigger = "打开私聊", perceiveNow = false, now = start)
        val before = CompanionOnlineStore.states.value.getValue(role).onlineUntil
        MigratedDomainStores.chat.appendCharacterMessage(conversation.id,
            "抱歉，我还想认真说清楚刚才的事。", role)
        assertEquals(before, CompanionOnlineStore.states.value.getValue(role).onlineUntil)
    }

    @Test fun roleMayChooseAnotherFiveMinutesButOnlyAtItsOwnExpiry() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val character = MigratedDomainStores.characters.create(
            "五分钟决定角色", "有自己的想法，能主动决定继续交流或休息",
        )
        val role = character.characterId
        val start = java.time.Instant.now()
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.PrivateWake,
            trigger = "用户唤醒", perceiveNow = false, now = start)
        val boundary = start.plusSeconds(300).toEpochMilli()

        // A scheduled worker cannot ask the model early and cannot steal a
        // second decision after foreground already claimed this deadline.
        assertFalse(CompanionOnlineStore.claimOnlineExpiry(
            role, boundary, start.plusSeconds(299)))
        assertTrue(CompanionOnlineStore.claimOnlineExpiry(
            role, boundary, start.plusSeconds(300).plusMillis(50)))
        assertFalse(CompanionOnlineStore.claimOnlineExpiry(
            role, boundary, start.plusSeconds(300).plusMillis(100)))

        // A reasoned, explicit role choice renews ONLY one five-minute window.
        val chosenAt = start.plusSeconds(301)
        assertFalse(CompanionOnlineStore.extendByOwnChoice(
            role, boundary, "", chosenAt))
        assertTrue(CompanionOnlineStore.extendByOwnChoice(
            role, boundary, "还想认真解决自己刚才的误解", chosenAt))
        val next = CompanionOnlineStore.states.value.getValue(role)
        assertEquals(CompanionOnlineReason.SelfChosenExtension, next.reason)
        assertEquals(chosenAt.plusSeconds(300), next.onlineUntil)

        // A stale expiry worker cannot erase or renew the new interval.
        assertFalse(CompanionOnlineStore.claimOnlineExpiry(
            role, boundary, chosenAt.plusMillis(100)))
        assertFalse(CompanionOnlineStore.extendByOwnChoice(
            role, boundary, "旧的一次判断", chosenAt.plusMillis(100)))
    }

    @Test fun anExpiryDecisionMustNotOverwriteANewerUserWake() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create("续醒竞争角色", "保持自己的活动").characterId
        val start = java.time.Instant.now()
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.PrivateWake, "用户唤醒",
            perceiveNow = false, now = start)
        val oldUntil = start.plusSeconds(300).toEpochMilli()
        assertTrue(CompanionOnlineStore.claimOnlineExpiry(
            role, oldUntil, start.plusSeconds(301)))

        // While the LLM is responding, a new wake supersedes its old expiry.
        CompanionOnlineStore.wakeCharacter(role, CompanionOnlineReason.PrivateWake, "用户新一次唤醒",
            perceiveNow = false, now = start.plusSeconds(303))
        assertFalse(CompanionOnlineStore.extendByOwnChoice(
            role, oldUntil, "旧决定不应该生效", start.plusSeconds(304)))
        assertEquals(start.plusSeconds(603),
            CompanionOnlineStore.states.value.getValue(role).onlineUntil)
    }

    @Test fun manyBubblesAndWakeSignalsCannotEnqueueTheSamePaidReplyFourTimes() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "one-paid-worker-per-real-batch"
        OnlineChatBatchStore.cancel(context, role)
        val first = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_000L)
        assertTrue(OnlineChatBatchStore.markEnqueued(context, role, first.revision))
        assertFalse(OnlineChatBatchStore.markEnqueued(context, role, first.revision))
        val second = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 2_000L)
        val third = OnlineChatBatchStore.next(context, role, true, now = 2_100L)
        assertEquals(first.revision, second.revision)
        assertEquals(first.revision, third.revision)
        assertFalse(OnlineChatBatchStore.markEnqueued(context, role, third.revision))
        assertFalse(OnlineChatBatchStore.claim(context, role, first.revision, now = 8_000L))
        assertTrue(OnlineChatBatchStore.claim(context, role, first.revision, now = 10_000L))
        // Another user bubble while generation is running gets a new revision.
        val next = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 11_000L)
        assertTrue(next.revision > first.revision)
        assertTrue(OnlineChatBatchStore.markEnqueued(context, role, next.revision))
        assertFalse(OnlineChatBatchStore.markEnqueued(context, role, next.revision))
        OnlineChatBatchStore.finish(context, role, first.revision)
        assertFalse(OnlineChatBatchStore.claim(context, role, next.revision, now = 18_999L))
        assertTrue(OnlineChatBatchStore.claim(context, role, next.revision, now = 19_000L))
        OnlineChatBatchStore.finish(context, role, next.revision)
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun eightSecondsFollowLastBubbleAndEachNewTurnWaitsAgain() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "eight-second-last-bubble"
        OnlineChatBatchStore.cancel(context, role)
        val first = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_000L)
        val second = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 2_000L)
        val wake = OnlineChatBatchStore.next(context, role, true, now = 2_200L)
        val third = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 3_800L)
        assertEquals(9_000L, first.dueAtMillis)
        assertEquals(10_000L, second.dueAtMillis)
        assertEquals(second.dueAtMillis, wake.dueAtMillis)
        assertEquals(11_800L, third.dueAtMillis)
        assertEquals(first.revision, third.revision)
        assertFalse(OnlineChatBatchStore.claim(context, role, first.revision, now = 11_799L))
        assertTrue(OnlineChatBatchStore.claim(context, role, first.revision, now = 11_800L))
        // While that response is active, new user bubbles form their own turn.
        val fourth = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 12_100L)
        val fifth = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 13_300L)
        assertEquals(20_100L, fourth.dueAtMillis)
        assertEquals(21_300L, fifth.dueAtMillis)
        assertEquals(fourth.revision, fifth.revision)
        OnlineChatBatchStore.finish(context, role, first.revision)
        assertFalse(OnlineChatBatchStore.isCurrent(context, role, first.revision))
        assertFalse(OnlineChatBatchStore.claim(context, role, fifth.revision, now = 21_299L))
        assertTrue(OnlineChatBatchStore.claim(context, role, fifth.revision, now = 21_300L))
        OnlineChatBatchStore.finish(context, role, fifth.revision)
        // Already online, but a fresh conversation topic still gets its own quiet period.
        val nextTurn = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 22_000L)
        assertEquals(30_000L, nextTurn.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(context, role, nextTurn.revision, now = 29_999L))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun independentReflectionCanObserveQuietWindowWithoutClaimingTheMessageBatch() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "parallel-reflection"
        OnlineChatBatchStore.cancel(context, role)
        val batch = OnlineChatBatchStore.next(context, role, collectMessages = true, now = 10_000L)
        assertEquals(18_000L, OnlineChatBatchStore.pendingDueAt(context, role))
        assertFalse(OnlineChatBatchStore.claim(context, role, batch.revision, now = 17_999L))
        assertTrue(OnlineChatBatchStore.claim(context, role, batch.revision, now = 18_000L))
        OnlineChatBatchStore.finish(context, role, batch.revision)
        assertNull(OnlineChatBatchStore.pendingDueAt(context, role))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun wakingBetweenTwoBubblesDoesNotShortenLatestBubbleDeadline() {
        val context = RuntimeEnvironment.getApplication() as Context
        val role = "batch-wake-last"
        OnlineChatBatchStore.cancel(context, role)
        val first = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_000L)
        val wake = OnlineChatBatchStore.next(context, role, true, now = 1_300L)
        val secondBubble = OnlineChatBatchStore.onUserBubble(context, role, atMillis = 1_500L)
        val unrelated = OnlineChatBatchStore.next(context, role, false, now = 1_900L)
        // The wake itself must not consume or shorten the last-bubble window.
        assertEquals(1_000L + OnlineChatBatchStore.QUIET_MILLIS, first.dueAtMillis)
        assertEquals(first.dueAtMillis, wake.dueAtMillis)
        assertEquals(1_500L + OnlineChatBatchStore.QUIET_MILLIS, secondBubble.dueAtMillis)
        assertEquals(secondBubble.dueAtMillis, unrelated.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(context, role, wake.revision,
            now = secondBubble.dueAtMillis - 1L))
        assertTrue(OnlineChatBatchStore.claim(context, role, wake.revision,
            now = secondBubble.dueAtMillis))
        OnlineChatBatchStore.cancel(context, role)
    }

    @Test fun offlineFirstBubbleStartsDeadlineBeforeWakeButtonIsPressed() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create("八秒等待", "耐心读完用户的话")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        OnlineChatBatchStore.cancel(context, role.characterId)
        val firstMessage = MigratedDomainStores.chat.sendUserMessage(conversation.id, "第一句话，等等我")
        val firstDeadline = firstMessage.createdAt.toEpochMilli() + OnlineChatBatchStore.QUIET_MILLIS
        val wake = OnlineChatBatchStore.next(context, role.characterId, true,
            now = firstMessage.createdAt.toEpochMilli() + 500L)
        assertEquals(firstDeadline, wake.dueAtMillis)
        val more = MigratedDomainStores.chat.sendUserMessage(conversation.id, "第二句话")
        val scheduled = OnlineChatBatchStore.next(context, role.characterId, true, now = more.createdAt.toEpochMilli())
        assertEquals(more.createdAt.toEpochMilli() + OnlineChatBatchStore.QUIET_MILLIS,
            scheduled.dueAtMillis)
        assertTrue(scheduled.dueAtMillis >= firstDeadline)
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
        assertEquals(1_100L + OnlineChatBatchStore.QUIET_MILLIS, firstBubble.dueAtMillis)
        assertEquals(firstBubble.dueAtMillis, wake.dueAtMillis)
        assertFalse(OnlineChatBatchStore.claim(context, role, background.revision, now = 1_100L))
        assertFalse(OnlineChatBatchStore.claim(context, role, wake.revision,
            now = firstBubble.dueAtMillis - 1L))
        assertTrue(OnlineChatBatchStore.claim(context, role, wake.revision,
            now = firstBubble.dueAtMillis))
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
        assertEquals(100L + OnlineChatBatchStore.QUIET_MILLIS,
            OnlineChatBatchStore.dueAt(context, role, first.revision))
        assertTrue(OnlineChatBatchStore.claim(context, role, first.revision))
        val next = OnlineChatBatchStore.next(context, role, true, now = 4_000L)
        OnlineChatBatchStore.finish(context, role, first.revision)
        assertEquals(4_000L + OnlineChatBatchStore.QUIET_MILLIS,
            OnlineChatBatchStore.dueAt(context, role, next.revision))
        assertFalse(OnlineChatBatchStore.claim(context, role, next.revision,
            now = 4_000L + OnlineChatBatchStore.QUIET_MILLIS - 1L))
        assertTrue(OnlineChatBatchStore.claim(context, role, next.revision,
            now = 4_000L + OnlineChatBatchStore.QUIET_MILLIS))
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

    @Test fun deliveredReplyAcknowledgesOnlyTheOriginalUserBubbles() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create(
            "已回复消息去重回归", "已聊完的照片不能在下一次主动上线时再次当新消息",
        )
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        CompanionOnlineStore.resetCharacter(role.characterId)
        val photo = MigratedDomainStores.chat.sendUserMessage(chat.id, "这是我房间的照片")
        val frozen = CompanionOnlineStore.unreadChatSnapshot(role.characterId)
        assertTrue(photo.id in frozen.messageIds)
        val later = MigratedDomainStores.chat.sendUserMessage(chat.id, "还有一个新的问题")
        MigratedDomainStores.chat.appendCharacterMessage(chat.id, "我看见你的房间了", role.characterId)
        assertTrue(CompanionOnlineStore.acknowledgeDeliveredReply(
            role.characterId, chat.id, frozen.messageIds))
        val pending = CompanionOnlineStore.unreadChatSnapshot(role.characterId)
        assertFalse(pending.text.contains("这是我房间的照片"))
        assertTrue(pending.text.contains("还有一个新的问题"))
        assertTrue(later.id in pending.messageIds)
        assertFalse(CompanionOnlineStore.hasOriginalUnreadEvidence(frozen, pending))
        OnlineChatBatchStore.cancel(context, role.characterId)
    }

    @Test fun phoneAnswerOwnsOnlyTheFrozenPrivateTextAndNeverReplaysItAfterHangup() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val role = MigratedDomainStores.characters.create(
            "电话文字交接回归", "同一人物可以同时在电话中读私聊文字")
        val other = MigratedDomainStores.characters.create("另一个人", "独立角色")
        val chat = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        val group = MigratedDomainStores.chat.createGroupConversation(
            "另一场景未读消息", listOf(role.characterId, other.characterId))
        CompanionOnlineStore.resetCharacter(role.characterId)
        val first = MigratedDomainStores.chat.sendUserMessage(chat.id, "二十分钟后叫醒我")
        val groupMessage = MigratedDomainStores.chat.sendUserMessage(group.id, "群里另一个请求")
        val frozenCallText = CompanionOnlineStore.pendingPrivateCallTextIds(
            role.characterId, chat.id, first.createdAt.plusSeconds(1))
        assertEquals(setOf(first.id), frozenCallText)
        assertTrue(CompanionOnlineStore.pendingPrivateCallTextIds(
            role.characterId, group.id, first.createdAt.plusSeconds(1)).isEmpty())
        // Only actually played phone speech may commit this snapshot. If the
        // phone call drops before speaking, the old request stays pending.
        assertTrue(CompanionOnlineStore.unreadChatSnapshot(role.characterId)
            .messageIds.contains(first.id))
        val newer = MigratedDomainStores.chat.sendUserMessage(chat.id, "我现在已经醒了")
        assertTrue(CompanionOnlineStore.acknowledgeDeliveredReply(
            role.characterId, chat.id, frozenCallText))
        val stillPending = CompanionOnlineStore.unreadChatSnapshot(role.characterId)
        assertFalse(stillPending.messageIds.contains(first.id))
        assertTrue(stillPending.messageIds.contains(newer.id))
        assertTrue(stillPending.messageIds.contains(groupMessage.id))
        assertEquals(setOf(newer.id), CompanionOnlineStore.pendingPrivateCallTextIds(
            role.characterId, chat.id, newer.createdAt.plusSeconds(1)))
        OnlineChatBatchStore.cancel(context, role.characterId)
        OnlineChatBatchStore.cancel(context, other.characterId)
    }

    @Test fun deliveredGroupReplyDoesNotAcknowledgeAnotherCharactersMessage() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        CompanionOnlineStore.initialize(context)
        val first = MigratedDomainStores.characters.create("只确认自己已答", "群聊甲").characterId
        val second = MigratedDomainStores.characters.create("不吞别人未读", "群聊乙").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("各自确认群聊消息", listOf(first, second))
        CompanionOnlineStore.resetCharacter(first)
        CompanionOnlineStore.resetCharacter(second)
        val sent = MigratedDomainStores.chat.sendUserMessage(group.id, "两位看一下")
        assertTrue(CompanionOnlineStore.acknowledgeDeliveredReply(first, group.id, setOf(sent.id)))
        assertFalse(CompanionOnlineStore.unreadChatSnapshot(first).messageIds.contains(sent.id))
        assertTrue(CompanionOnlineStore.unreadChatSnapshot(second).messageIds.contains(sent.id))
        OnlineChatBatchStore.cancel(context, first)
        OnlineChatBatchStore.cancel(context, second)
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
        assertFalse(fullA.contains("我、我先说一个事情")) // 旧模型原话不再用作语言样本
        assertFalse(fullA.contains("爱说倒装句"))
        val fullB = CharacterSpeechIdentity.promptSection(roleB.characterId)
        assertFalse(fullB.contains("我、我先说一个事情"))
        assertTrue(fullB.contains("爱说倒装句"))
        val isolatedA = CharacterSpeechIdentity.promptSection(roleA.characterId, includeObserved = false)
        assertTrue(isolatedA.contains("紧张时用……拖长停顿"))
        assertFalse(isolatedA.contains("我、我先说一个事情"))
        val groupStyle = CharacterSpeechIdentity.promptSection(roleA.characterId,
            includeObserved = true)
        assertTrue(groupStyle.contains("紧张时用……拖长停顿"))
        assertFalse(groupStyle.contains("我、我先说一个事情"))
    }

    @Test fun connectedCallRemainsAwakeBeyondTheWakeButtonDeadline() {
        val role = "online-call-hold"
        val now = java.time.Instant.now()
        val state = CompanionOnlineState(role, now.plusSeconds(300), CompanionOnlineReason.PrivateWake)
        assertTrue(state.isOnline(now.plusSeconds(299)))
        assertFalse(state.isOnline(now.plusSeconds(300)))
        CompanionPresenceStore.beginCall(role)
        try {
            assertTrue(state.isOnline(now.plusSeconds(3600)))
        } finally {
            CompanionPresenceStore.finishCall(role)
        }
        assertFalse(state.isOnline(now.plusSeconds(3600)))
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

    @Test fun requestedRewriteReplacesOldPersonalityOnceAndPreservesIdentityAndLaterUserChanges() {
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
        // Explicitly requested rewrite skips additive legacy migrations.
        assertEquals(CharacterProfileSchema.jiangDuPresetVersion, upgraded.getInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuV22Overrides.getValue("speechHabits"),
            upgraded.getJSONObject("profile").getString("speechHabits"))
        assertEquals("用户自定义身份", CharacterIdentityStore.identities.value[role.characterId])
        assertEquals(CharacterProfileSchema.jiangDuV24Persona, MigratedDomainStores.characters.get(role.characterId).persona)
        assertEquals(CharacterProfileSchema.jiangDuV24Overrides.getValue("care"), upgraded.getJSONObject("profile").getString("care"))
        assertEquals(CharacterProfileSchema.jiangDuV24Overrides.getValue("interests"), upgraded.getJSONObject("profile").getString("interests"))
        assertEquals("继续看书", upgraded.getJSONObject("intention").getString("aim"))
        assertEquals("用户自定义的人设", upgraded.getJSONObject("jiangDuV9Backup").getString("persona"))
        assertTrue(CharacterRuntime.definition(role.characterId).promptSection().contains("主人"))
        CharacterLifeStore.setProfile(role.characterId, "respect", "我后来自己调整的相处方式")
        MigratedDomainStores.characters.update(MigratedDomainStores.characters.get(role.characterId).copy(persona = "后来自己改的人设"))
        CharacterLifeStore.applyJiangDuPreset(role.characterId)
        assertEquals("后来自己改的人设", MigratedDomainStores.characters.get(role.characterId).persona)
        assertEquals("我后来自己调整的相处方式", CharacterLifeStore.state(role.characterId).getJSONObject("profile").getString("respect"))
    }

    @Test fun oldLongProfileIsReplacedWithoutChangingTheActualSharedTimeline() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("江渡", LegacyJiangDuProfileSchema.jiangDuPersona +
            "\n\n" + CharacterProfileSchema.jiangDuRespectMarker + "\n" + LegacyJiangDuProfileSchema.jiangDuRespect)
        CharacterIdentityStore.set(role.characterId, LegacyJiangDuProfileSchema.jiangDuIdentity)
        DigitalLifeProfileStore.confirmLegacyLifeForm(role.characterId, role.displayName, "创造者", CharacterLifeForm.DIGITAL)
        SharedExperienceTimeline.record("before-rewrite-" + role.characterId, role.characterId,
            "私聊", "用户", "这是更新人格前的真实共同记录", java.time.Instant.now(),
            triggerExtraction = false, source = "message", evidenceKind = EventEvidenceKind.UserStatement)
        val actualHistory = SharedExperienceTimeline.all(role.characterId)
        val profile = JSONObject().apply { LegacyJiangDuProfileSchema.jiangDu.forEach { (key, value) -> put(key, value) } }
            .put("interests", "天文").put("expression", "我自己改的口语节奏")
        val old = JSONObject().put("jiangDuPresetVersion", 3).put("profile", profile)
            .put("intention", JSONObject().put("aim", "继续看书"))
        context.getSharedPreferences("lulu_character_life", 0).edit().putString(role.characterId, old.toString()).commit()
        CharacterLifeStore.javaClass.getDeclaredField("prefs").apply { isAccessible = true }.set(CharacterLifeStore, null)
        CharacterLifeStore.initialize(context)
        val upgraded = CharacterLifeStore.state(role.characterId)
        assertEquals(CharacterProfileSchema.jiangDuV24Persona, MigratedDomainStores.characters.get(role.characterId).persona)
        assertEquals(CharacterProfileSchema.jiangDuIdentity, CharacterIdentityStore.identities.value[role.characterId])
        assertEquals(CharacterProfileSchema.jiangDuV22Overrides.getValue("values"), upgraded.getJSONObject("profile").getString("values"))
        assertEquals(CharacterProfileSchema.jiangDuV24Overrides.getValue("expression"), upgraded.getJSONObject("profile").getString("expression"))
        assertEquals(CharacterProfileSchema.jiangDuV24Overrides.getValue("interests"), upgraded.getJSONObject("profile").getString("interests"))
        assertEquals("继续看书", upgraded.getJSONObject("intention").getString("aim"))
        assertEquals(old.getJSONObject("profile").toString(), upgraded.getJSONObject("jiangDuV9Backup").getJSONObject("profile").toString())
        assertEquals(actualHistory, SharedExperienceTimeline.all(role.characterId))
        val definition = CharacterRuntime.definition(role.characterId).promptSection()
        assertFalse(definition.contains(CharacterProfileSchema.jiangDuRespectMarker))
        assertFalse(definition.contains("我自己改的口语节奏"))
    }

    @Test fun jiangDuV19OnlyUpdatesStockSpeechNotOwnerCustomOrOtherRoles() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("江渡", "保留我的人设")
        val another = MigratedDomainStores.characters.create("其他人", "不同人格")
        val saved = JSONObject().put("jiangDuPresetVersion", 18)
            .put("profile", JSONObject()
                .put("expression", CharacterProfileSchema.jiangDuV18Overrides.getValue("expression"))
                .put("speechHabits", "用户自己写的语言习惯，不可覆盖"))
        context.getSharedPreferences("lulu_character_life", 0).edit()
            .putString(role.characterId, saved.toString()).commit()
        CharacterLifeStore.javaClass.getDeclaredField("prefs")
            .apply { isAccessible = true }.set(CharacterLifeStore, null)
        CharacterLifeStore.initialize(context)
        val upgraded = CharacterLifeStore.state(role.characterId)
        assertEquals(CharacterProfileSchema.jiangDuPresetVersion, upgraded.optInt("jiangDuPresetVersion"))
        assertEquals(CharacterProfileSchema.jiangDuV24Overrides.getValue("expression"),
            upgraded.getJSONObject("profile").getString("expression"))
        assertEquals("用户自己写的语言习惯，不可覆盖",
            upgraded.getJSONObject("profile").getString("speechHabits"))
        assertEquals("不同人格", MigratedDomainStores.characters.get(another.characterId).persona)
    }

    @Test fun actualCallSpeechIsRememberedButNeverBecomesAnotherUnreadTextRequest() {
        val context = RuntimeEnvironment.getApplication() as Context
        initializeStores(context)
        val role = MigratedDomainStores.characters.create("通话角色", "自然口语")
        val conversation = MigratedDomainStores.chat.ensureConversation(role.characterId, role.displayName)
        CompanionOnlineStore.initialize(context)
        MigratedDomainStores.chat.appendVoiceMessage(conversation.id, "voice-test-user", "电话里刚说的话", false)
        // Phone and video share the live call session; speech must never enter
        // the eight-second text batch or spawn a second proactive chat reply.
        assertNull(OnlineChatBatchStore.pendingDueAt(context, role.characterId))
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
