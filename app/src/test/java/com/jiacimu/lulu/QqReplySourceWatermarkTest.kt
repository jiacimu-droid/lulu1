package com.jiacimu.lulu

import com.jiacimu.lulu.data.InMemoryLuluChatStore
import com.jiacimu.lulu.data.LuluGroupMember
import com.jiacimu.lulu.data.MigratedDomainStores
import com.jiacimu.lulu.data.SharedExperienceTimeline
import com.jiacimu.lulu.data.MemoryTestEnvironment
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
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
}
