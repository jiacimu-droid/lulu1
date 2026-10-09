package com.jiacimu.lulu

import com.jiacimu.lulu.data.LuluChatMessage
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class QqMessageGroupingTest {
    private val at = Instant.parse("2026-10-10T08:00:00Z")
    private fun bubble(batch: String?, seconds: Long) = LuluChatMessage(
        conversationId = "conversation", sender = LuluChatMessage.Sender.Character,
        authorCharacterId = "character", content = "消息", createdAt = at.plusSeconds(seconds),
        replyBatchId = batch,
    )

    @Test fun sameGenerationSharesAvatarEvenIfStreamingOverTwoMinutes() {
        assertTrue(sameQqMessageGroup(bubble("round-1", 0), bubble("round-1", 150)))
    }

    @Test fun autonomousSecondRoundStartsNewAvatarWithoutUserResponse() {
        assertFalse(sameQqMessageGroup(bubble("round-1", 0), bubble("round-2", 5)))
        assertFalse(sameQqMessageGroup(bubble("round-1", 0), bubble(null, 5)))
    }

    @Test fun legacyMessagesStillUseBoundedTimeGrouping() {
        assertTrue(sameQqMessageGroup(bubble(null, 0), bubble(null, 40)))
        assertFalse(sameQqMessageGroup(bubble(null, 0), bubble(null, 150)))
    }
}
