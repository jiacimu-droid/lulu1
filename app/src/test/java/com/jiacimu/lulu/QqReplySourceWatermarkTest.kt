package com.jiacimu.lulu

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.CompanionActionRuntime
import com.jiacimu.lulu.data.ChatGenerationActivity
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MemoryTestEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class QqReplySourceWatermarkTest {
    @Before fun setup() {
        MemoryTestEnvironment.release()
        val app = RuntimeEnvironment.getApplication()
        LuluRepositories.initialize(app)
        SharedExperienceTimeline.initialize(app)
    }

    @After fun cleanup() { MemoryTestEnvironment.release() }

    @Test fun privateReplyCannotTreatEditedThenRestoredTextAsOriginalVersion() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val role = MigratedDomainStores.characters.create("私聊水位角色").characterId
        val conversation = store.ensureConversation(role, "私聊")
        val first = store.sendUserMessage(conversation.id, "原话")
        val captured = captureReplySourceWatermark(conversation.id, role, setOf(first.id), store)
        assertNotNull(captured)
        assertTrue(isReplySourceWatermarkCurrent(captured!!, store))
        // A character's unrelated persisted message does not change the user source.
        store.appendCharacterMessage(conversation.id, "我听见了。", role)
        assertTrue(isReplySourceWatermarkCurrent(captured, store))
        assertTrue(store.editMessage(first.id, "我其实不是这个意思"))
        assertFalse(isReplySourceWatermarkCurrent(captured, store))
        // Even identical restored wording is a new revision, not the old source.
        assertTrue(store.editMessage(first.id, "原话"))
        assertFalse(isReplySourceWatermarkCurrent(captured, store))
        val corrected = captureReplySourceWatermark(conversation.id, role, setOf(first.id), store)!!
        assertTrue(isReplySourceWatermarkCurrent(corrected, store))
        store.sendUserMessage(conversation.id, "再补充一句")
        assertFalse(isReplySourceWatermarkCurrent(corrected, store))
        assertNull(captureReplySourceWatermark(conversation.id, "another-role", setOf(first.id), store))
    }

    @Test fun groupReplyUsesEachMembersOwnTimelineRevisionAndLiveMembership() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val a = MigratedDomainStores.characters.create("群水位甲").characterId
        val b = MigratedDomainStores.characters.create("群水位乙").characterId
        val c = MigratedDomainStores.characters.create("群水位丙").characterId
        val outsider = MigratedDomainStores.characters.create("群外人").characterId
        val group = store.createGroupConversation("版本群", listOf(a, b, c))
        val user = store.sendUserMessage(group.id, "刚才说的那句话")
        val aBefore = captureReplySourceWatermark(group.id, a, setOf(user.id), store)!!
        val bBefore = captureReplySourceWatermark(group.id, b, setOf(user.id), store)!!
        assertTrue(isReplySourceWatermarkCurrent(aBefore, store))
        assertTrue(isReplySourceWatermarkCurrent(bBefore, store))
        assertNull(captureReplySourceWatermark(group.id, outsider, setOf(user.id), store))
        store.appendCharacterMessage(group.id, "甲先说了一句", a)
        assertTrue(isReplySourceWatermarkCurrent(bBefore, store))
        assertTrue(store.editMessage(user.id, "纠正一下"))
        assertFalse(isReplySourceWatermarkCurrent(aBefore, store))
        assertFalse(isReplySourceWatermarkCurrent(bBefore, store))
        val revised = captureReplySourceWatermark(group.id, a, setOf(user.id), store)!!
        assertTrue(store.updateGroupConversation(group.id,
            group.groupChat!!.copy(members = listOf(LuluGroupMember(b), LuluGroupMember(c)))))
        assertFalse(isReplySourceWatermarkCurrent(revised, store))
        assertNull(captureReplySourceWatermark(group.id, a, setOf(user.id), store))
        val bRevised = captureReplySourceWatermark(group.id, b, setOf(user.id), store)!!
        assertTrue(store.deleteMessage(user.id))
        assertFalse(isReplySourceWatermarkCurrent(bRevised, store))
    }

    @Test fun joiningOrRenamingGroupInvalidatesEarlierGeneratedGroupReply() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val a = MigratedDomainStores.characters.create("房间变更甲").characterId
        val b = MigratedDomainStores.characters.create("房间变更乙").characterId
        val c = MigratedDomainStores.characters.create("房间变更丙").characterId
        val group = store.createGroupConversation("聊天小组", listOf(a, b))
        val msg = store.sendUserMessage(group.id, "一起讨论一下")
        val first = captureReplySourceWatermark(group.id, a, setOf(msg.id), store)!!
        val changed = group.groupChat!!.copy(
            members = group.groupChat!!.members + LuluGroupMember(c),
        )
        assertTrue(store.updateGroupConversation(group.id, changed))
        assertFalse(isReplySourceWatermarkCurrent(first, store))
        val second = captureReplySourceWatermark(group.id, a, setOf(msg.id), store)!!
        assertTrue(isReplySourceWatermarkCurrent(second, store))
        assertTrue(store.updateGroupConversation(group.id, changed.copy(name = "新的小组名")))
        assertFalse(isReplySourceWatermarkCurrent(second, store))
    }

    @Test fun userCorrectionDuringTypingLeadStopsOldBubblesBeforeAnyDelivery() = runBlocking {
        val role = MigratedDomainStores.characters.create("慢回复水位角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "慢回复")
        val first = MigratedDomainStores.chat.sendUserMessage(conversation.id, "先回答这个")
        val captured = captureReplySourceWatermark(conversation.id, role, setOf(first.id))!!
        val pending = async {
            appendRoleReplyWithPacing(conversation.id, role, "慢回复水位角色",
                parseCharacterReplyPresentation("旧答案第一句。⟪BUBBLE⟫旧答案第二句。"),
                actionableUserMessageIds = setOf(first.id),
                sourceWatermark = captured)
        }
        delay(100)
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "不对，我改主意了")
        assertEquals("", pending.await())
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.none {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
    }

    @Test fun actionExecutorDoesNotReportSuccessWhenCorrectedReplyWasNeverSent() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val role = MigratedDomainStores.characters.create("动作回执水位角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "动作回执")
        val first = MigratedDomainStores.chat.sendUserMessage(conversation.id, "先说原问题")
        val args = JSONObject().put("text", "针对旧问题的回复")
            .put("_replyEvidenceIds", JSONArray().put(first.id))
        val pending = async {
            CompanionActionRuntime.execute(app, role, "send_private_message", args)
        }
        // Wait for the real sender to enter its paced-delivery lease,
        // rather than racing the executor's synchronous setup.
        withTimeout(8_000) {
            while (!ChatGenerationActivity.isRunning(conversation.id)) delay(10)
        }
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "刚才说错了，按这条来")
        val result = pending.await()
        assertFalse("Stale reply cannot be marked as a successful action", result.success)
        assertTrue(MigratedDomainStores.chat.messages(conversation.id).value.none {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
    }

    @Test fun groupActionIgnoresOtherMembersUnreadAndSendsOnlyOwnBubble() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val a = MigratedDomainStores.characters.create("群聊先发言成员").characterId
        val b = MigratedDomainStores.characters.create("群聊后发言成员").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("后宫测试群", listOf(a, b))
        val user = MigratedDomainStores.chat.sendUserMessage(group.id, "把我打入冷宫吗")
        val earlierRole = MigratedDomainStores.chat.appendCharacterMessage(group.id, "我先回一句", a)
        // Online perception includes the user's raw ID AND another character's
        // ID. Only the former is a user-message watermark for member B.
        val ids = setOf(user.id, earlierRole.id)
        assertEquals(setOf(user.id),
            com.jiacimu.lulu.data.replyUserMessageIdsForDestination(group.id, b, ids))
        val args = JSONObject().put("groupId", group.id).put("text", "那当然不会啦")
            .put("_replyEvidenceIds", JSONArray().put(user.id).put(earlierRole.id))
        val outcome = CompanionActionRuntime.execute(app, b, "send_group_message", args)
        assertTrue(outcome.summary, outcome.success)
        assertTrue(MigratedDomainStores.chat.messages(group.id).value.any {
            it.authorCharacterId == b && it.content == "那当然不会啦"
        })
    }

    @Test fun groupActionStillStopsOnNewUserCorrectionWithOtherRoleEvidence() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        val a = MigratedDomainStores.characters.create("群消息纠正甲").characterId
        val b = MigratedDomainStores.characters.create("群消息纠正乙").characterId
        val group = MigratedDomainStores.chat.createGroupConversation("纠正测试群", listOf(a, b))
        val user = MigratedDomainStores.chat.sendUserMessage(group.id, "之前的问题")
        val olderRole = MigratedDomainStores.chat.appendCharacterMessage(group.id, "先接一句", a)
        val args = JSONObject().put("groupId", group.id).put("text", "应该停止的旧回复")
            .put("_replyEvidenceIds", JSONArray().put(olderRole.id).put(user.id))
        val pending = async { CompanionActionRuntime.execute(app, b, "send_group_message", args) }
        withTimeout(8_000) {
            while (!ChatGenerationActivity.isRunning(group.id)) delay(10)
        }
        MigratedDomainStores.chat.sendUserMessage(group.id, "不对，我改主意了")
        val outcome = pending.await()
        assertFalse(outcome.success)
        assertTrue(MigratedDomainStores.chat.messages(group.id).value.none {
            it.authorCharacterId == b && it.content == "应该停止的旧回复"
        })
    }

    @Test fun finalCommitGuardRejectsEditedSourceAndNewUserCorrection() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val role = MigratedDomainStores.characters.create("提交水位角色").characterId
        val conversation = store.ensureConversation(role, "提交门禁")
        val original = store.sendUserMessage(conversation.id, "原来的意思")
        val first = captureReplySourceWatermark(conversation.id, role, setOf(original.id), store)!!
        assertTrue(store.editMessage(original.id, "已经纠正"))
        val rejected = store.appendCharacterMessageIfSourceCurrent(
            conversation.id, "不能发送的旧回复", role, null, "old-batch",
            false, "", sourceStillCurrent = { isReplySourceWatermarkCurrent(first, store) },
        )
        assertNull(rejected)
        assertEquals(0, store.messages(conversation.id).value.count {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
        val corrected = captureReplySourceWatermark(conversation.id, role, setOf(original.id), store)!!
        store.sendUserMessage(conversation.id, "再补充一句")
        assertNull(store.appendCharacterMessageIfSourceCurrent(
            conversation.id, "同样不应发送", role, null, "old-batch-2",
            false, "", sourceStillCurrent = { isReplySourceWatermarkCurrent(corrected, store) },
        ))
    }

    @Test fun concurrentCorrectionCannotSlipBetweenFinalValidationAndPersistedBubble() {
        val store = InMemoryLuluChatStore().also { it.initialize(RuntimeEnvironment.getApplication()) }
        val role = MigratedDomainStores.characters.create("并发提交水位角色").characterId
        val conversation = store.ensureConversation(role, "原子提交")
        val original = store.sendUserMessage(conversation.id, "原问题")
        val watermark = captureReplySourceWatermark(conversation.id, role, setOf(original.id), store)!!
        val guardEntered = java.util.concurrent.CountDownLatch(1)
        val releaseGuard = java.util.concurrent.CountDownLatch(1)
        val editStarted = java.util.concurrent.CountDownLatch(1)
        val editFinished = java.util.concurrent.CountDownLatch(1)
        val sent = java.util.concurrent.atomic.AtomicReference<com.jiacimu.lulu.data.LuluChatMessage?>()
        val errors = java.util.concurrent.atomic.AtomicReference<Throwable?>()
        val sender = Thread {
            try {
                sent.set(store.appendCharacterMessageIfSourceCurrent(
                    conversation.id, "真正送达的第一句", role, null, "concurrent-batch",
                    false, "", sourceStillCurrent = {
                        guardEntered.countDown()
                        check(releaseGuard.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        isReplySourceWatermarkCurrent(watermark, store)
                    },
                ))
            } catch (error: Throwable) { errors.set(error) }
        }
        val editor = Thread {
            try {
                editStarted.countDown()
                check(store.editMessage(original.id, "我改主意了"))
            } catch (error: Throwable) { errors.set(error) }
            finally { editFinished.countDown() }
        }
        sender.start()
        try {
            assertTrue(guardEntered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            editor.start()
            assertTrue(editStarted.await(5, java.util.concurrent.TimeUnit.SECONDS))
            // The editor cannot commit while the final check owns the store lock.
            assertEquals(1L, editFinished.count)
        } finally { releaseGuard.countDown() }
        sender.join(5_000)
        editor.join(5_000)
        assertFalse(sender.isAlive)
        assertFalse(editor.isAlive)
        assertNull(errors.get())
        assertNotNull(sent.get())
        assertEquals(1, store.messages(conversation.id).value.count {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        })
        assertFalse(isReplySourceWatermarkCurrent(watermark, store))
    }

    @Test fun correctionBetweenTwoBubblesKeepsFirstReceiptButStopsSecond() = runBlocking {
        val role = MigratedDomainStores.characters.create("气泡中途纠正角色").characterId
        val conversation = MigratedDomainStores.chat.ensureConversation(role, "气泡中断")
        val first = MigratedDomainStores.chat.sendUserMessage(conversation.id, "最初的问题")
        val captured = captureReplySourceWatermark(conversation.id, role, setOf(first.id))!!
        val pending = async {
            appendRoleReplyWithPacing(conversation.id, role, "气泡中途纠正角色",
                parseCharacterReplyPresentation("第一句已送达。⟪BUBBLE⟫第二句不该再发。"),
                actionableUserMessageIds = setOf(first.id),
                sourceWatermark = captured)
        }
        withTimeout(8_000) {
            while (MigratedDomainStores.chat.messages(conversation.id).value.none {
                    it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
                }) delay(25)
        }
        MigratedDomainStores.chat.sendUserMessage(conversation.id, "停一下，我改问题了")
        assertEquals("", pending.await())
        val actual = MigratedDomainStores.chat.messages(conversation.id).value.filter {
            it.sender == com.jiacimu.lulu.data.LuluChatMessage.Sender.Character
        }
        assertEquals(1, actual.size)
        assertEquals("第一句已送达。", actual.single().content)
    }
}
