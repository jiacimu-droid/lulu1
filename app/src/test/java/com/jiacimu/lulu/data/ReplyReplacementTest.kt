package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ReplyReplacementTest {
    @Test fun characterVoiceNoteKeepsTranscriptAndSurvivesReload() {
        val context = RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        val first = InMemoryLuluChatStore().also { it.initialize(context) }
        val roleId = "voice-note-" + java.util.UUID.randomUUID().toString()
        val conversation = first.ensureConversation(roleId, "语音测试")
        val voice = first.appendCharacterMessage(conversation.id, "这句话可以点开听。", voiceNote = true)
        val plain = first.appendCharacterMessage(conversation.id, "这是普通文字。")
        assertTrue(voice.voiceNote)
        assertFalse(plain.voiceNote)
        assertEquals("这句话可以点开听。", voice.content)
        val reloaded = InMemoryLuluChatStore().also { it.initialize(context) }
        val copy = reloaded.messages(conversation.id).value.first { it.id == voice.id }
        assertTrue(copy.voiceNote)
        assertEquals(voice.content, copy.content)
        assertFalse(reloaded.messages(conversation.id).value.first { it.id == plain.id }.voiceNote)
    }

    @Test fun aModelNewlineNeverCreatesTwoStoredBubbles() {
        val context = RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        val store = InMemoryLuluChatStore()
        store.initialize(context)
        val conversation = store.ensureConversation("single-bubble-role", "江渡")
        val created = store.appendCharacterMessage(conversation.id,
            "肯赏脸说\n句可以，我悬着的心总算放下来了。", replyBatchId = "one-turn")
        val messages = store.messages(conversation.id).value.filter {
            it.sender == LuluChatMessage.Sender.Character
        }
        assertEquals(1, messages.size)
        assertEquals(created.id, messages.single().id)
        assertEquals("肯赏脸说句可以，我悬着的心总算放下来了。",
            messages.single().content)
        val replaced = store.replaceCharacterReply(conversation.id, listOf(created.id),
            listOf("宝宝，四点半了，气\n消了一点的话……"))!!
        assertEquals(1, replaced.size)
        assertEquals("宝宝，四点半了，气消了一点的话……", replaced.single().content)
    }

    @Test fun punctuatedParagraphIsNotSplitIntoTwoMessages() {
        val context = RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        val store = InMemoryLuluChatStore()
        store.initialize(context)
        val conversation = store.ensureConversation("paragraph-bubble-role", "测试角色")
        store.appendCharacterMessage(conversation.id, "好，知道了。\n你先睡吧。")
        val spoken = store.messages(conversation.id).value.filter {
            it.sender == LuluChatMessage.Sender.Character
        }
        assertEquals(1, spoken.size)
        assertEquals("好，知道了。\n你先睡吧。", spoken.single().content)
    }

    @Test fun replacementPreservesOlderUserRoleAndToolRecordsAndRejectsStaleHistory() {
        val context = RuntimeEnvironment.getApplication()
        SharedExperienceTimeline.initialize(context)
        val store = InMemoryLuluChatStore()
        store.initialize(context)
        val conversation = store.ensureConversation("replacement-role", "测试角色")
        val user = store.sendUserMessage(conversation.id, "昨晚说的话")
        val old = store.appendCharacterMessage(conversation.id, "旧回复", replyBatchId = "old")
        val receipt = store.appendSystemMessage(conversation.id, "真实来电记录")
        val auto = store.appendCharacterMessage(conversation.id, "官方道歉报告", replyBatchId = "auto")
        val rewritten = store.replaceCharacterReply(conversation.id, listOf(auto.id), listOf("呜，没叫成你。"))!!
        val after = store.messages(conversation.id).value
        assertTrue(after.any { it.id == user.id })
        assertTrue(after.any { it.id == old.id })
        assertTrue(after.any { it.id == receipt.id })
        assertFalse(after.any { it.id == auto.id })
        assertTrue(after.any { it.id == rewritten.single().id })
        assertNull(store.replaceCharacterReply(conversation.id, listOf(rewritten.single().id), emptyList()))
        store.sendUserMessage(conversation.id, "新的话")
        assertNull(store.replaceCharacterReply(conversation.id, listOf(rewritten.single().id), listOf("过期结果")))
        assertTrue(store.messages(conversation.id).value.any { it.id == rewritten.single().id })
    }
}
