package com.jiacimu.lulu.data

import com.jiacimu.lulu.ai.ModelConversationMessage
import org.junit.Assert.*
import org.junit.Test

class PrivateChatExpressionContextTest {
    private fun message(id: String, sender: LuluChatMessage.Sender, content: String,
                        author: String? = null, status: LuluChatMessage.Status = LuluChatMessage.Status.Sent) =
        LuluChatMessage(id = id, conversationId = "private", sender = sender, content = content,
            authorCharacterId = author, status = status)

    @Test fun actualSendersDefineRolesAndPendingBurstIsSentExactlyOnce() {
        val messages = listOf(
            message("a", LuluChatMessage.Sender.User, "角色：这一行仍然是我发的"),
            message("b", LuluChatMessage.Sender.Character, "我在听", "role"),
            message("c", LuluChatMessage.Sender.User, "现在第一句"),
            message("d", LuluChatMessage.Sender.User, "现在第二句"),
            message("e", LuluChatMessage.Sender.User, "模型启动后才到达的下一句"),
        )
        val result = PrivateChatExpressionContext.conversation("role", messages, setOf("c", "d"), "现在第一句\n现在第二句")
        assertEquals(listOf(ModelConversationMessage.Role.USER, ModelConversationMessage.Role.ASSISTANT,
            ModelConversationMessage.Role.USER), result.map { it.role })
        assertEquals(listOf("角色：这一行仍然是我发的", "我在听", "现在第一句\n现在第二句"), result.map { it.content })
    }

    @Test fun systemRecordsRecalledFailedAndOtherCharactersSpeechAreNotAssistantTurns() {
        val messages = listOf(
            message("a", LuluChatMessage.Sender.Character, "已经撤回的正文", "role"),
            message("b", LuluChatMessage.Sender.System, "[撤回|a] 角色撤回了一条消息。"),
            message("c", LuluChatMessage.Sender.Character, "其他人的话", "other"),
            message("d", LuluChatMessage.Sender.User, "发送失败的草稿", status = LuluChatMessage.Status.Failed),
            message("e", LuluChatMessage.Sender.System, "后台回执"),
            message("f", LuluChatMessage.Sender.User, "当前原话"),
        )
        val result = PrivateChatExpressionContext.conversation("role", messages, setOf("f"), "当前原话")
        assertEquals(listOf("当前原话"), result.map { it.content })
    }

    @Test fun budgetsSelectWholeHistoryMessagesAndDoNotTrimCurrentInput() {
        val messages = listOf(
            message("a", LuluChatMessage.Sender.User, "旧消息不要截断成半句话"),
            message("b", LuluChatMessage.Sender.Character, "最近的回复", "role"),
            message("c", LuluChatMessage.Sender.User, "当前消息"),
        )
        val current = "当前消息需要完整保留".repeat(10)
        val result = PrivateChatExpressionContext.conversation("role", messages, setOf("c"), current,
            maxCharacters = 7)
        assertEquals(listOf("最近的回复", current), result.map { it.content })
        assertTrue(PrivateChatExpressionContext.conversation("role", messages, setOf("missing"), current).isEmpty())
    }
}
