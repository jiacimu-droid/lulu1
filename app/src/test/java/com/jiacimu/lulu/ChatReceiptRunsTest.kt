package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import org.junit.Assert.*
import org.junit.Test

class ChatReceiptRunsTest {
    private fun message(id: String, sender: LuluChatMessage.Sender) = LuluChatMessage(id, "chat", sender, id)
    @Test fun onlyAdjacentSystemReceiptsFoldAndOriginalOrderIsPreserved() {
        val records = listOf(message("s1", LuluChatMessage.Sender.System), message("s2", LuluChatMessage.Sender.System),
            message("user", LuluChatMessage.Sender.User), message("s3", LuluChatMessage.Sender.System))
        val runs = groupChatReceiptRuns(records)
        assertEquals(listOf(2, 1, 1), runs.map { it.messages.size })
        assertEquals(records, runs.flatMap { it.messages })
        assertEquals(runs.size, runs.map { it.key }.toSet().size)
    }
    @Test fun newReceiptKeepsFoldKeyStableAndDuplicateDeliveryDoesNotDuplicateKeys() {
        val first = message("s1", LuluChatMessage.Sender.System)
        val second = message("s2", LuluChatMessage.Sender.System)
        val before = groupChatReceiptRuns(listOf(first)).first().key
        val after = groupChatReceiptRuns(listOf(first, second, second))
        assertEquals(before, after.single().key)
        assertEquals(2, after.single().messages.size)
    }
}
