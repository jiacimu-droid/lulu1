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
