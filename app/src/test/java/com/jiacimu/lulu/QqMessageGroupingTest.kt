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

    @Test fun heartVoiceUsesReadableParagraphsWithoutChangingAuthoredWords() {
        val original = "学了两个小时跑回来喊累，还冷不丁甩了个张大嘴巴的震惊猫猫头。" +
            "估计算是学到脑袋发懵了。" +
            "这时候可别再跟她聊什么深奥的，先顺毛哄着让她把紧绷的神经松下来再说。"
        val result = heartVoiceParagraphs(original)
        assertTrue(result.size >= 2)
        assertEquals(original, result.joinToString(""))
        assertEquals(listOf("很想她。"), heartVoiceParagraphs("很想她。"))
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

    @Test fun realParagraphBreaksAreSeparateDeliveredBubbles() {
        val text = "你还在忙呀？\n\n嗯，没事。\n我等你。"
        val parsed = parseCharacterReplyPresentation(text)
        assertEquals(listOf("你还在忙呀？", "嗯，没事。\n我等你。"),
            semanticReplyBubbles(parsed.content))
    }

    @Test fun searchResultWithMultipleCompleteIdeasNeverBecomesGiantLastBubble() {
        val parts = (1..15).map { "我找到了第${it}个可靠来源，内容和出处都得等真正查完再发给你。" }
        val actual = semanticReplyBubbles(parts.joinToString("⟪BUBBLE⟫"))
        assertEquals(parts, actual)
        assertTrue(actual.none { it.length > 85 })
    }

    @Test fun longHumanSentencesBecomeSeveralReadableBubblesWithoutRewritingFacts() {
        val original = "宝宝，我刚才在电话里没有真正收到小红书的搜索结果，网页其实还停在首页。" +
            "你让我随便找一篇，我也想试试，可惜搜索页面确实超时了。" +
            "我不想装成已经读完，也不想又把问题甩给你。"
        val result = semanticReplyBubbles(original)
        assertTrue("应该按完整句子拆开", result.size >= 2)
        assertEquals("所有原话必须保留", original, result.joinToString(""))
        assertTrue(result.none { it.endsWith("不是") || it.endsWith("可是") })
    }

    @Test fun anAutonomousReplyAboutStudyFatigueUsesNaturalChatBubbles() {
        val original = "辛苦啦宝宝，快把书合上歇会儿。怎么还配个这么震惊的猫猫头，这是学到什么难题了，把我们小脑袋瓜都给看懵了？"
        // A full model-selected utterance may be split into real persisted
        // bubbles at sentence boundaries without requiring another API call.
        val result = semanticReplyBubbles(original)
        assertTrue("完整日常回复不应挤在一个长气泡里", result.size >= 2)
        assertEquals(original, result.joinToString(""))
        assertFalse(result.any { it.endsWith("，") })
    }

    @Test fun clarificationAndPhoneContextAreSeparateReadableTurns() {
        val original = "是‘我收好了’呀，傻瓜。" +
            "刚才电话里你不是连喊三声‘我去’，说好惊喜吗，听得我心里高兴，" +
            "就顺手把你那句话收藏进辞海里存着了。" +
            "怎么问完这句话就挂电话了，这会儿是真打算睡了吗，宝宝？"
        val bubbles = semanticReplyBubbles(original)
        assertTrue("独立解释、通话回忆与追问不应挤成一两个大气泡", bubbles.size >= 3)
        assertEquals(original, bubbles.joinToString(""))
        assertEquals("是‘我收好了’呀，傻瓜。", bubbles.first())
        assertTrue(bubbles.none { it.endsWith("把") || it.endsWith("不是") })
    }

    @Test fun aLongSingleSentenceMaySplitAtNaturalCommaButNeverMidword() {
        val original = "我刚刚在想这件事情为什么一直都没有解决，也想着是不是我搞错了打开网页的顺序，不过至少现在能够确定首页内容是真实读到的，别再把那个当成搜索结果啦。"
        val result = semanticReplyBubbles(original)
        assertTrue(result.size >= 2)
        assertEquals(original, result.joinToString(""))
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
