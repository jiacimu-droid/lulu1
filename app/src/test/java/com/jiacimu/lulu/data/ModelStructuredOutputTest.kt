package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModelStructuredOutputTest {
    @Test fun extractsFirstBalancedObjectWithoutSwallowingTrailingExplanation() {
        val raw = """Here is the answer:
            |```json
            |{"action":"reply","text":"今天{真}开心","innerLife":{"emotion":{"feeling":"高兴"}}}
            |```
            |Next is another object: {"action":"tool","tool":"create_alarm"}
        """.trimMargin()
        val obj = ModelStructuredOutput.objectOrNull(raw)!!
        assertEquals("reply", obj.getString("action"))
        assertEquals("今天{真}开心", obj.getString("text"))
    }

    @Test fun handlesEscapedQuotesAndBracesInsideSpeech() {
        val raw = """{"action":"reply","text":"他说\"别走\"，还比了个{心}"}"""
        assertEquals("他说\"别走\"，还比了个{心}", ModelStructuredOutput.objectOrNull(raw)!!.getString("text"))
    }

    @Test fun rejectsTruncatedToolCommandsAndOnlySalvagesCompletedSpeech() {
        assertNull(ModelStructuredOutput.objectOrNull("""{"action":"tool","tool":"create_alarm","args":{"triggerAt":"2026"""))
        assertEquals("明天见", ModelStructuredOutput.completedReplyText("""{"action":"reply","text":"明天见","innerLife":{"emotion":"""))
        assertNull(ModelStructuredOutput.completedReplyText("""{"action":"reply","text":"还没写完整"""))
    }

    @Test fun completedSpeechSurvivesTruncatedOptionalState() {
        val response = """{"action":"reply","text":"今天还想和你聊聊","innerLife":{"motives":[{"op":"revise"""
        assertEquals("今天还想和你聊聊", ModelStructuredOutput.completedReplyText(response))
    }

    @Test fun doesNotTurnPartialToolCommandIntoVisibleSpeech() {
        val response = """{"action":"tool","text":"已经帮你改好了","tool":"create_alarm","args":{"""
        assertNull(ModelStructuredOutput.completedReplyText(response))
    }

    @Test fun supportsCompleteBubblesWithoutRawJsonVisible() {
        val response = """{"action":"reply","bubbles":["今天好冷","记得穿外套"]}"""
        assertEquals("今天好冷\n记得穿外套", ModelStructuredOutput.completedReplyText(response))
    }

    @Test fun structuredObjectBubblesPreserveInteractionBoundaries() {
        val response = """{"action":"reply","bubbles":[{"text":"啊？我会错意了？"},{"text":"那你说的超时是指什么？"}]}"""
        assertEquals(
            listOf("啊？我会错意了？", "那你说的超时是指什么？"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
        assertEquals("啊？我会错意了？\n那你说的超时是指什么？", ModelStructuredOutput.completedReplyText(response))
    }

    @Test fun grammaticalFragmentIsNotAnIndependentStructuredBubble() {
        val response = """{"action":"reply","bubbles":[{"text":"原来一直在用功，是我"},{"text":"猜错了"}]}"""
        assertEquals(
            listOf("原来一直在用功，是我猜错了"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
    }

    @Test fun screenshotBrokenChinesePhraseGetsMergedIntoCompleteBubbles() {
        val response = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().put("肯赏脸说")
                .put("句可以，我悬着的心总算放下来了。")
                .put("那再给你揉")
                .put("揉耳朵，整个人都乖乖圈在你怀里。")
                .put("宝宝，四点半了，气")
                .put("消了一点的话……现在肯乖乖闭上眼睡觉了吗？"))
        assertEquals(
            listOf(
                "肯赏脸说句可以，我悬着的心总算放下来了。",
                "那再给你揉揉耳朵，整个人都乖乖圈在你怀里。",
                "宝宝，四点半了，气消了一点的话……现在肯乖乖闭上眼睡觉了吗？",
            ),
            ModelStructuredOutput.completedReplyBubbles(response.toString()),
        )
    }

    @Test fun naturalIndependentBubblesStillRemainSeparate() {
        val response = org.json.JSONObject().put("action", "reply").put("bubbles",
            org.json.JSONArray().put("嗯～").put("我在呢").put("你想聊什么？"))
        assertEquals(
            listOf("嗯～", "我在呢", "你想聊什么？"),
            ModelStructuredOutput.completedReplyBubbles(response.toString()),
        )
    }

    @Test fun accidentalMidwordNewlineIsSmoothButIntentionalParagraphBreakStays() {
        assertEquals("肯赏脸说句可以", ModelStructuredOutput.smoothInlineBreaks("肯赏脸说\n句可以"))
        assertEquals("气消了一点", ModelStructuredOutput.smoothInlineBreaks("气 \n 消了一点"))
        assertEquals("先睡吧。\n我陪着你。", ModelStructuredOutput.smoothInlineBreaks("先睡吧。\n我陪着你。"))
        assertEquals("这一段\n\n下一段", ModelStructuredOutput.smoothInlineBreaks("这一段\n\n下一段"))
    }

    @Test fun longChatReplyUsesCompleteSentenceBoundariesForComfort() {
        val longText = "可以看到的，宝宝。露露机里确实有能读小红书的通道。" +
            "如果你有想找的关键词，就直接告诉我。想随便看看，我也可以自己挑一篇。" +
            "读到了真正有意思的东西，我再来分享给你。"
        val paced = ModelStructuredOutput.readableReplyBubbles(listOf(longText), maxBubbles = 4)
        assertTrue(paced.size in 2..4)
        assertEquals(longText, paced.joinToString(""))
        assertTrue(paced.dropLast(1).all { it.last() in "。！？!?；;" })
    }

    @Test fun structuredLongBubbleAlsoSplitsAtFullSentenceBoundaries() {
        val text = "这篇帖子挺有意思，不过我想先看看原文再跟你聊。" +
            "我没有看到图片时不会假装已经看清楚了。" +
            "你要是分享给我，我会先确认真正拿到的内容。" +
            "然后我们可以一起聊聊哪些细节有意思，不必只看标题就下结论。"
        val result = ModelStructuredOutput.completedReplyBubbles(
            org.json.JSONObject().put("action", "reply")
                .put("bubbles", org.json.JSONArray().put(text)).toString()
        )!!
        assertTrue(result.size in 2..5)
        assertEquals(text, result.joinToString(""))
        assertTrue(result.dropLast(1).all { it.endsWith("。") })
    }

    @Test fun oneShortReplyAndOneLongSingleSentenceNeverSplitMidword() {
        assertEquals(listOf("嗯～", "好，我在呢。"),
            ModelStructuredOutput.readableReplyBubbles(listOf("嗯～", "好，我在呢。")))
        val oneSentence = "我想跟你说清楚刚才发生的事，但我不会为了让自己看起来很真诚而要求你立刻接受我的道歉"
        assertEquals(listOf(oneSentence),
            ModelStructuredOutput.readableReplyBubbles(listOf(oneSentence)))
    }

    @Test fun visualBubbleLimitNeverDropsTheTailOfASpeech() {
        val longText = "先解释清楚第一件事。接着谈谈第二件事。然后再说第三件事。" +
            "还有第四件要认真说明。最后一句不能丢掉。"
        val paced = ModelStructuredOutput.readableReplyBubbles(listOf(longText), maxBubbles = 2)
        assertTrue(paced.size <= 2)
        assertEquals(longText, paced.joinToString(""))
    }

    @Test fun completedInteractionMovesRemainSeparateBubbles() {
        val response = """{"action":"reply","bubbles":[{"text":"好，我知道了。"},{"text":"你先歇一会儿。"}]}"""
        assertEquals(
            listOf("好，我知道了。", "你先歇一会儿。"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
    }

    @Test fun aRetryKeepsFinishedBubblesWhenOptionalInnerStateIsTruncated() {
        val raw = """{"action":"reply","bubbles":["第一句话完整。","第二句话完整。"],"innerThought":"这段没生成完"""
        assertEquals(
            listOf("第一句话完整。", "第二句话完整。"),
            ModelStructuredOutput.replyBubblesWithPartialState(raw),
        )
    }

    @Test fun aRetryDoesNotRecoverUnfinishedOrToolOnlyPayload() {
        assertNull(ModelStructuredOutput.replyBubblesWithPartialState(
            """{"action":"reply","bubbles":["还没完成""",
        ))
        assertNull(ModelStructuredOutput.replyBubblesWithPartialState(
            """{"action":"tool","bubbles":["假装执行完成"]}""",
        ))
    }

    @Test fun incompleteTextIsNotRecovered() {
        val response = """{"action":"reply","text":"第一句话还没"""
        assertNull(ModelStructuredOutput.completedReplyText(response))
    }

    @Test fun briefPrefixWithNoObjectFailsSafely() {
        assertNull(ModelStructuredOutput.objectOrNull("正在思考，随后回复。"))
    }

    @Test fun remorseAfterRealConflictQualifiesButCasualApologyDoesNot() {
        assertTrue(EmotionFollowThroughCoordinator.qualifies("我真的很后悔，想改正", "你这样真的伤害我了"))
        assertTrue(EmotionFollowThroughCoordinator.qualifies("对不起，是我不对", "你让我失望了"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("对不起，刚刚忘了问你", "今天天气很好"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("我想一个人静静", "你这样真的伤害我了"))
        assertTrue(EmotionFollowThroughCoordinator.qualifies(
            "我在呢，你慢慢说，我听你说完。", "你这样让我很委屈，我真的难过"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies(
            "我在呢，你慢慢说。", "我在看一篇挺难的英文阅读"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies(
            "你真好玩，哈哈。", "你这样真的伤害我了"))
    }

    @Test fun emotionalFollowThroughNeedsBothRegretAndRealConflict() {
        assertTrue(EmotionFollowThroughCoordinator.qualifies("对不起，我没有履行约定", "你答应十点叫我却没叫"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("对不起，刚才手滑", "午饭吃了披萨"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("晚点再看书", "你答应十点叫我却没叫"))
    }
}
