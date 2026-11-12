package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatReplyTurnActionsTest {
    private fun role(id: String, batch: String?) = LuluChatMessage(id = id, conversationId = "chat",
        sender = LuluChatMessage.Sender.Character, content = id, replyBatchId = batch)
    @Test fun autonomousTurnDoesNotConsumeEverythingAfterTheLastUserMessage() {
        val user = LuluChatMessage(id = "user", conversationId = "chat", sender = LuluChatMessage.Sender.User, content = "晚安")
        val all = listOf(user, role("old-1", "previous"), role("old-2", "previous"),
            role("morning-1", "morning"), role("morning-2", "morning"))
        assertEquals(listOf("morning-1", "morning-2"), regeneratableLatestTurn(all, "morning-1")!!.generatedMessageIds)
        assertNull(regeneratableLatestTurn(all, "old-1"))
    }
    @Test fun noUserAnchorIsNeededAndLegacyNeighboursStaySeparate() {
        assertEquals(listOf("auto"), regeneratableLatestTurn(listOf(role("auto", "autonomous")), "auto")!!.generatedMessageIds)
        assertEquals(listOf("last"), regeneratableLatestTurn(listOf(role("before", null), role("last", null)), "last")!!.generatedMessageIds)
    }
    @Test fun alternateFirstDraftNeverContainsTheOldBubbles() {
        val user = LuluChatMessage(id = "user-5", conversationId = "chat",
            sender = LuluChatMessage.Sender.User, content = "你能刷视频吗")
        val original = role("old-bubble-1", "batch")
        val second = role("old-bubble-2", "batch")
        val snapshot = listOf(user, original, second)
        val turn = regeneratableLatestTurn(snapshot, original.id)!!
        val before = replyHistoryBeforeTurn(snapshot, turn.generatedMessageIds)
        assertEquals(listOf("user-5"), before.map { it.id })
        assertTrue(before.none { it.id in turn.generatedMessageIds })
        val fresh = freshReplyWindow(snapshot, turn.generatedMessageIds)
        assertTrue(fresh.earlierHistory.isEmpty())
        assertEquals(listOf("user-5"), fresh.incomingMessages.map { it.id })
        assertEquals(setOf("old-bubble-1", "old-bubble-2",
            "old-bubble-1:group:role-1", "old-bubble-2:group:role-1"),
            excludedReplyTimelineIds(turn.generatedMessageIds, "role-1"))
    }

    @Test fun newUserSpeechPreventsReplacingAnOlderTurn() {
        val all = listOf(role("auto", "a"), LuluChatMessage(id = "new", conversationId = "chat",
            sender = LuluChatMessage.Sender.User, content = "我还有话说"))
        assertNull(regeneratableLatestTurn(all, "auto"))
    }
}
