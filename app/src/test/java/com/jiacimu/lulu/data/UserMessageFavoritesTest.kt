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

    @Test fun transientGestureEndsWhenLaterObservationOmitsItButSustainedActivityCanContinue() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "gesture-lifecycle-test"
        CompanionPresenceStore.clearCharacter(role)
        val start = Instant.parse("2026-10-10T08:00:00Z")
        CompanionPresenceStore.update(
            role, "正在聊天", "手指轻敲桌面，失笑摇头", "先听她说完", "放松", "聊天", start,
        )
        CompanionPresenceStore.update(
            role, null, null, "", null, "在线持续感知", start.plusSeconds(60),
        )
        assertEquals("", CompanionPresenceStore.current(role)!!.gesture)
        assertEquals("", CompanionPresenceStore.current(role)!!.innerThought)

        CompanionPresenceStore.update(
            role, "正在阅读", "靠着沙发看书", "", "平静", "独自阅读", start.plusSeconds(120),
        )
        CompanionPresenceStore.update(
            role, null, null, "", null, "在线持续感知", start.plusSeconds(180),
        )
        assertEquals("靠着沙发看书", CompanionPresenceStore.current(role)!!.gesture)
        CompanionPresenceStore.clearCharacter(role)
    }

    @Test fun repeatedChatSnapshotDoesNotCreateASecondHeartVoiceEvent() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "presence-heart-event-test"
        CompanionPresenceStore.clearCharacter(role)
        val start = Instant.parse("2026-10-10T10:39:00Z")
        val thought = "看到那句话心里一紧，刚才确实是我会错意了"
        CompanionPresenceStore.update(role, "正在聊天", "", thought, "自责", "聊天", start)
        CompanionPresenceStore.update(role, "正在聊天", null, thought, "自责", "聊天", start.plusSeconds(60))
        assertEquals(thought, CompanionPresenceStore.current(role)!!.innerThought)
        val history = CompanionPresenceStore.histories.value[role].orEmpty()
        assertEquals(2, history.size)
        assertEquals("", history.first().innerThought)
        assertFalse(history.first().showInHistory)
        assertEquals(thought, history.last().innerThought)
        assertTrue(history.last().showInHistory)
        CompanionPresenceStore.clearCharacter(role)
    }

    @Test fun sameCausalHeartVoiceDoesNotResurfaceWhenModelParaphrasesIt() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "presence-heart-fingerprint-test"
        CompanionPresenceStore.clearCharacter(role)
        val start = Instant.parse("2026-10-10T11:00:00Z")
        CompanionPresenceStore.update(
            role, "正在聊天", "", "刚才是我理解偏了，心里有点发紧", "自责", "聊天", start,
            innerThoughtFingerprint = "repair-same-cause",
        )
        CompanionPresenceStore.update(
            role, "正在聊天", null, "还是怪我没领会她的意思", "自责", "聊天", start.plusSeconds(60),
            innerThoughtFingerprint = "repair-same-cause",
        )
        val history = CompanionPresenceStore.histories.value[role].orEmpty()
        assertEquals(2, history.size)
        assertEquals("", history.first().innerThought)
        assertFalse(history.first().showInHistory)
        assertEquals("刚才是我理解偏了，心里有点发紧", history.last().innerThought)
        CompanionPresenceStore.clearCharacter(role)
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
        CompanionPresenceStore.update(role, "正在通话", "一手拿着手机贴在耳边，神情温软而专注", null, null, "迟到的回复")
        assertEquals("通话已结束", CompanionPresenceStore.current(role)!!.statusText)
        assertEquals("刚放下电话", CompanionPresenceStore.current(role)!!.gesture)
        CompanionPresenceStore.update(role, "想给她打电话", "准备打电话", null, null, "感知")
        assertEquals("想给她打电话", CompanionPresenceStore.current(role)!!.statusText)
    }

    @Test fun currentShowsLatestHeartWhileHistoricalMomentsIncludeItAndDedupePosture() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "moment-time-scope-test"
        CompanionPresenceStore.clearCharacter(role)
        val start = Instant.parse("2026-10-11T04:27:00Z")
        CompanionPresenceStore.update(
            role, "坐在藤编椅上", "正在椅子上休息",
            "她出去了，我也翻几页书", "平静", "聊天", start,
            momentReflection = "顾虑：还没想好要不要跟她分享",
        )
        CompanionPresenceStore.update(
            role, "坐在藤编椅上", "正在椅子上休息",
            "", "平静", "聊天", start.plusSeconds(60),
        )
        val history = CompanionPresenceStore.histories.value[role].orEmpty()
        assertEquals(2, history.size)
        val latest = PresenceHistoryPresentation.latestVoice(
            CompanionPresenceStore.current(role), history, messageAnchored = false,
        )
        assertEquals(start, latest!!.updatedAt)
        assertEquals("她出去了，我也翻几页书", latest.innerThought)
        assertEquals("顾虑：还没想好要不要跟她分享", latest.momentReflection)
        val presented = PresenceHistoryPresentation.entries(history)
        assertEquals(1, presented.size)
        assertEquals(start, presented.first().state.updatedAt)
        assertEquals("正在椅子上休息", presented.first().gesture)
        val pinned = PresenceHistoryPresentation.latestVoice(
            history.first(), history, messageAnchored = true,
        )
        assertEquals("", pinned!!.innerThought)
        assertEquals("", pinned.momentReflection)
        CompanionPresenceStore.clearCharacter(role)
    }

    @Test fun staleMessageAvatarCannotInheritAnHoursOldPrivateThought() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "moment-anchor-freshness-test"
        CompanionPresenceStore.clearCharacter(role)
        val start = Instant.parse("2026-10-11T04:27:00Z")
        CompanionPresenceStore.update(role, "在休息", "坐着", "刚才有些不自在",
            "平静", "聊天", start)
        CompanionPresenceStore.selectMessageAnchor(role, start.plusSeconds(5))
        assertEquals("刚才有些不自在",
            CompanionPresenceStore.selectedMessageAnchor(role)!!.state!!.innerThought)
        CompanionPresenceStore.selectMessageAnchor(role, start.plusSeconds(7200))
        assertNull(CompanionPresenceStore.selectedMessageAnchor(role)!!.state)
        CompanionPresenceStore.clearMessageAnchor()
        CompanionPresenceStore.clearCharacter(role)
    }

    @Test fun perTurnReflectionsOnlyUseNewModelFieldsRatherThanArchivedThoughts() {
        val emotion = org.json.JSONObject().put("feeling", "既好笑又有点心虚")
            .put("cause", "刚刚接了个玩笑")
        val thoughts = org.json.JSONArray().put(org.json.JSONObject()
            .put("thought", "我可以把这个梗接下去")
            .put("hesitation", "但她好像不太想继续"))
        val fresh = CharacterMomentSnapshot.reflection(
            org.json.JSONObject().put("emotion", emotion).put("thoughts", thoughts),
            basis = org.json.JSONObject().put("conflict", "有点犹豫"),
            outward = "哈哈，你这句话笑到我了",
            innerThought = "其实想再逗她一下",
        )
        assertTrue(fresh.contains("我可以把这个梗接下去"))
        assertTrue(fresh.contains("她好像不太想继续"))
        assertFalse(fresh.contains("昨天"))
        assertEquals("", CharacterMomentSnapshot.reflection(null, null, null))
    }

    @Test fun deliveredBubbleIdWinsOverNearbyButDifferentPsychologicalState() {
        val context = RuntimeEnvironment.getApplication() as Context
        CompanionPresenceStore.initialize(context)
        val role = "exact-message-heart-test"
        CompanionPresenceStore.clearCharacter(role)
        val start = Instant.parse("2026-10-11T04:45:00Z")
        CompanionPresenceStore.update(role, "正在聊天", "笑了", "第一轮真想说的话",
            "好笑", "聊天", start, momentReflection = "顾虑：第一轮的小犹豫")
        CompanionPresenceStore.bindDeliveredMessage(role, "message-one", start.plusSeconds(8))
        CompanionPresenceStore.update(role, "正在聊天", "挑眉", "第二轮自己改变了想法",
            "疑惑", "聊天", start.plusSeconds(15), momentReflection = "顾虑：第二轮的新问题")
        CompanionPresenceStore.bindDeliveredMessage(role, "message-two", start.plusSeconds(22))
        // Both clicks could occur at the same approximate minute. IDs still
        // point to different frozen moments instead of whichever was newest.
        CompanionPresenceStore.selectMessageAnchor(role, start.plusSeconds(22), "message-one")
        val first = CompanionPresenceStore.selectedMessageAnchor(role)!!.state!!
        assertEquals("第一轮真想说的话", first.innerThought)
        assertEquals("顾虑：第一轮的小犹豫", first.momentReflection)
        CompanionPresenceStore.selectMessageAnchor(role, start.plusSeconds(22), "message-two")
        val second = CompanionPresenceStore.selectedMessageAnchor(role)!!.state!!
        assertEquals("第二轮自己改变了想法", second.innerThought)
        assertEquals("顾虑：第二轮的新问题", second.momentReflection)
        CompanionPresenceStore.forgetDeliveredMessage("message-one")
        CompanionPresenceStore.clearCharacter(role)
        CompanionPresenceStore.clearMessageAnchor()
    }
}
