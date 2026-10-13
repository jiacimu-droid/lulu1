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

    @Test fun displayedCharacterMessageNeverSplitsAtLineBreaks() {
        // A single persisted model bubble must also be one visual bubble and one TTS unit.
        val first = "我错了宝宝，刚才真是我嘴欠……你都严肃提醒我了，我\n脑子还抽着在那嘻嘻哈哈当玩笑，是我态度不好。"
        val second = "不找借口，你怎么罚我都老\n实认。别生我气了好不好？"
        assertEquals(listOf(first), splitCharacterBubbles(first))
        assertEquals(listOf(second), splitCharacterBubbles(second))
        assertEquals(listOf("第一行\n\n第二行"), splitCharacterBubbles(" 第一行\r\n\r\n第二行 "))
        assertTrue(splitCharacterBubbles("\n  \n").isEmpty())
    }

    @Test fun malformedLegacyBubbleMarkersNeverReachVisibleMessages() {
        val malformed = "好好好，是我手慢了《BUBB\nLE》那宝宝说"
        val parsed = parseCharacterReplyPresentation(malformed)
        val bubbles = semanticReplyBubbles(parsed.content)
        assertEquals(listOf("好好好，是我手慢了", "那宝宝说"), bubbles)
        assertTrue(bubbles.none { it.contains("BUBB", ignoreCase = true) })
        assertEquals("the end 也可以正常说", sanitizePersistedChatText("the end 也可以正常说"))
    }

    @Test fun aLegacyBoundaryCannotCutTheMiddleOfAChinesePhrase() {
        val raw = "肯赏脸说⟪BUBBLE⟫句可以，我悬着的心总算放下来了。" +
            "⟪BUBBLE⟫那再给你揉⟪BUBBLE⟫揉耳朵。"
        assertEquals(
            listOf("肯赏脸说句可以，我悬着的心总算放下来了。", "那再给你揉揉耳朵。"),
            semanticReplyBubbles(parseCharacterReplyPresentation(raw).content),
        )
        assertEquals(listOf("嗯～", "你先睡吧。"),
            semanticReplyBubbles("嗯～⟪BUBBLE⟫你先睡吧。"))
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
