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

    @Test fun onlyExplicitSemanticMarkerCreatesAnotherBubble() {
        val raw = "果然猜中了\n，还真是跟家里人聊天呢。⟪BUBBLE⟫既然在大姨旁边，就先专心陪长辈说话，顺便把热乎的鸡蛋吃了。"
        val parsed = parseCharacterReplyPresentation(raw)
        assertEquals(2, semanticReplyBubbles(parsed.content).size)
        assertEquals("果然猜中了\n，还真是跟家里人聊天呢。",
            semanticReplyBubbles(parsed.content)[0])
        assertEquals("既然在大姨旁边，就先专心陪长辈说话，顺便把热乎的鸡蛋吃了。",
            semanticReplyBubbles(parsed.content)[1])
        assertEquals(1, semanticReplyBubbles("果然猜中了\n，还真是跟家里人聊天呢。").size)
        assertFalse(parsed.content.contains("⟪BUBBLE⟫⟪BUBBLE⟫"))
    }

    @Test fun malformedLegacyBubbleMarkersNeverReachVisibleMessages() {
        val malformed = "好好好，是我手慢了《BUBB\nLE》那宝宝说"
        val parsed = parseCharacterReplyPresentation(malformed)
        val bubbles = semanticReplyBubbles(parsed.content)
        assertEquals(listOf("好好好，是我手慢了", "那宝宝说"), bubbles)
        assertTrue(bubbles.none { it.contains("BUBB", ignoreCase = true) })
        assertEquals("the end 也可以正常说", sanitizePersistedChatText("the end 也可以正常说"))
    }

    @Test fun repeatedNewlinesAndNoSeparatorStayOneBubble() {
        val text = "你还在忙呀？\n\n嗯，没事。\n我等你。"
        val parsed = parseCharacterReplyPresentation(text)
        assertEquals(listOf(text), semanticReplyBubbles(parsed.content))
    }

    @Test fun directivesDoNotCreateBubblesOrLeakIntoContent() {
        val parsed = parseCharacterReplyPresentation("⟪QUOTE:abc⟫我看到了。\n你先吃饭⟪BUBBLE⟫别着急。")
        assertEquals("abc", parsed.quoteMessageId)
        assertEquals(listOf("我看到了。\n你先吃饭", "别着急。"), semanticReplyBubbles(parsed.content))
    }

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
