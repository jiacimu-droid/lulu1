package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ModelStructuredOutputTest {
    @Test fun individualVoiceSpeechTracksArePositionAlignedAndNotPartOfTranscripts() {
        val json = org.json.JSONObject().put("action", "message").put("bubbles",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("text", "我在呢。").put("delivery", "voice")
                    .put("speechText", "[softly] 我在呢。"))
                .put(org.json.JSONObject().put("text", "你说。").put("delivery", "text"))
                .put(org.json.JSONObject().put("text", "慢慢来。").put("delivery", "voice")
                    .put("speechText", "[hesitant] 慢慢来。")))
        val tracks = ModelStructuredOutput.bubbleSpeechTracks(json)
        assertEquals(listOf("[softly] 我在呢。", "", "[hesitant] 慢慢来。"), tracks)
        val plan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = "我在呢。" + com.jiacimu.lulu.SemanticBubbleSeparator +
                    "你说。" + com.jiacimu.lulu.SemanticBubbleSeparator + "慢慢来。",
                bubbleDeliveryModes = ModelStructuredOutput.bubbleDeliveryModes(json),
                bubbleSpeechTracks = tracks))
        assertEquals(listOf(true, false, true), plan.map { it.voiceNote })
        // Segmentation may normalize whitespace next to the direction tags.
        // The invariant is the same spoken words and preserved audio-only cues.
        assertEquals(tracks.map(com.jiacimu.lulu.VoicePerformance::plain),
            plan.map { com.jiacimu.lulu.VoicePerformance.plain(it.speechText) })
        assertTrue(plan[0].speechText.contains("[softly]"))
        assertEquals("", plan[1].speechText)
        assertTrue(plan[2].speechText.contains("[hesitant]"))
        assertEquals(listOf("我在呢。", "你说。", "慢慢来。"), plan.map { it.text })
    }

    @Test fun allBubblesCanBeVoiceNotesInOneCharacterTurn() {
        val words = listOf("来啦。", "坐我旁边。", "今天辛苦了。", "想听我说话吗？", "我在呢。")
        val json = org.json.JSONObject().put("action", "message").put("bubbles",
            org.json.JSONArray().apply {
                words.forEach { put(org.json.JSONObject().put("text", it).put("delivery", "voice")) }
            })
        val modes = ModelStructuredOutput.bubbleDeliveryModes(json)
        assertEquals(List(words.size) { "voice" }, modes)
        val presentation = com.jiacimu.lulu.CharacterReplyPresentation(
            content = words.joinToString(com.jiacimu.lulu.SemanticBubbleSeparator),
            bubbleDeliveryModes = modes,
        )
        val planned = com.jiacimu.lulu.characterOutgoingBubblePlan(presentation)
        assertEquals(words, planned.map { it.text })
        assertTrue(planned.all { it.voiceNote })
        // These are five independent recordings, not one combined TTS request.
        assertEquals(5, planned.count { it.voiceNote })
    }

    @Test fun duplicateBubbleTextStillUsesItsOwnDeliveryChoice() {
        val words = listOf("嗯。", "嗯。", "那就这样。", "好的。")
        val modes = listOf("text", "voice", "text", "voice")
        val plan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = words.joinToString(com.jiacimu.lulu.SemanticBubbleSeparator),
                bubbleDeliveryModes = modes,
            ),
        )
        assertEquals(words, plan.map { it.text })
        assertEquals(listOf(false, true, false, true), plan.map { it.voiceNote })
    }

    @Test fun voiceDeliveryCanBeEntirelyAbsentWithoutChangingText() {
        val json = org.json.JSONObject().put("action", "message").put("bubbles",
            org.json.JSONArray().put("第一句").put(
                org.json.JSONObject().put("text", "第二句").put("delivery", "text")))
        assertEquals(listOf("text", "text"), ModelStructuredOutput.bubbleDeliveryModes(json))
        val plan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = "第一句" + com.jiacimu.lulu.SemanticBubbleSeparator + "第二句",
                bubbleDeliveryModes = ModelStructuredOutput.bubbleDeliveryModes(json),
            ),
        )
        assertEquals(2, plan.size)
        assertFalse(plan.any { it.voiceNote })
    }

    @Test fun structuredVoiceChoiceKeepsExactTranscriptAndSurroundingText() {
        val raw = """{"action":"reply","bubbles":[{"text":"先给你一个抱抱。"},{"text":"过来，亲亲你，mua～","delivery":"voice"},{"text":"好啦，今天早点休息。"}]}"""
        assertEquals(listOf("过来，亲亲你，mua～"), ModelStructuredOutput.voiceBubbleTexts(raw))
        assertEquals(listOf("先给你一个抱抱。", "过来，亲亲你，mua～", "好啦，今天早点休息。"),
            ModelStructuredOutput.authoredReplyBubbles(raw))
        assertTrue(ModelStructuredOutput.voiceBubbleTexts("""{"action":"tool","bubbles":[{"text":"假语音","delivery":"voice"}]}""").isEmpty())
    }

    @Test fun textBubblesAreDecidedBeforeDeliveryModeForAnyLength() {
        val short = "今天辛苦啦。"
        val spoken = "我想认真告诉你，今天能和你一起聊天是很开心的事。你想听的话，点一下这条语音就能听到我的声音。"
        val content = short + com.jiacimu.lulu.SemanticBubbleSeparator + spoken
        val textPlan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = content, bubbleDeliveryModes = listOf("text", "text")))
        val voicePlan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = content, bubbleDeliveryModes = listOf("text", "voice")))
        assertEquals("Choosing voice must not change any bubble boundary",
            textPlan.map { it.text }, voicePlan.map { it.text })
        assertEquals(listOf(short) + com.jiacimu.lulu.semanticReplyBubbles(spoken),
            voicePlan.map { it.text })
        assertFalse(voicePlan.first().voiceNote)
        assertTrue(voicePlan.drop(1).all { it.voiceNote })
        assertTrue("Normal short sentences may form separate voice bubbles",
            voicePlan.drop(1).size >= 2)
    }

    @Test fun longVoiceBubbleUsesTheSameNaturalMessageBoundariesAsText() {
        val long = "我刚刚想到一个可以聊的话题，先说说我的看法吧。最近遇见了一件有趣的小事，让我忍不住想与你分享。" +
            "不过事情不是我最开始以为的那样，后来发现还有另一种解释。要不我从头慢慢讲给你听？" +
            "最后才发现，原来我们两个人的观察角度完全不同。这样的讨论还挺有意思的。"
        val content = listOf("知道啦。", long, "嗯，记住了。").joinToString(
            com.jiacimu.lulu.SemanticBubbleSeparator)
        val textPlan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = content, bubbleDeliveryModes = listOf("text", "text", "text")))
        val voicePlan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = content, bubbleDeliveryModes = listOf("text", "voice", "voice"),
                bubbleSpeechTracks = listOf("", "[softly]" + long, ""),
            ))
        assertEquals(textPlan.map { it.text }, voicePlan.map { it.text })
        assertEquals("知道啦。", voicePlan.first().text)
        assertFalse(voicePlan.first().voiceNote)
        assertEquals("嗯，记住了。", voicePlan.last().text)
        val voiceParts = voicePlan.subList(1, voicePlan.lastIndex)
        assertEquals(com.jiacimu.lulu.semanticReplyBubbles(long), voiceParts.map { it.text })
        assertTrue(voiceParts.size >= 2)
        assertTrue(voiceParts.all { it.voiceNote })
        assertEquals(long, voiceParts.joinToString("") { it.text })
        assertTrue("Audio tracks must never repeat the full transcript in each segment",
            voiceParts.all { segment ->
                segment.speechText.isBlank() ||
                    com.jiacimu.lulu.VoicePerformance.plain(segment.speechText) == segment.text
            })
    }

    @Test fun legacyVoiceChoiceIsAlsoSegmentedWithoutChangingTheWordsOrShortNotes() {
        val long = ("我想认真和你说这件事情。先听我把原因讲明白。" +
            "你不需要立刻回答。我们慢慢讨论就好。").repeat(3)
        val plan = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = "好。" + com.jiacimu.lulu.SemanticBubbleSeparator +
                    long + com.jiacimu.lulu.SemanticBubbleSeparator + "晚安。",
                voiceBubbleTexts = listOf("好。", long),
            ),
        )
        assertEquals("好。", plan.first().text)
        assertTrue(plan.first().voiceNote)
        assertEquals("晚安。", plan.last().text)
        assertFalse(plan.last().voiceNote)
        val longParts = plan.subList(1, plan.lastIndex)
        assertTrue(longParts.size >= 2)
        assertTrue(longParts.all { it.voiceNote })
        assertEquals(long, longParts.joinToString("") { it.text })
    }

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

    @Test fun twelveIndependentBubblesSurviveWithoutSilentTruncation() {
        val expected = (1..12).map { "第${it}次反应！" }
        val raw = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().apply { expected.forEach(::put) }).toString()
        assertEquals(expected, ModelStructuredOutput.authoredReplyBubbles(raw))
        assertEquals(expected, ModelStructuredOutput.completedReplyBubbles(raw))
    }

    @Test fun structuredReplyKeepsModelAuthoredBubbleRhythmAtDelivery() {
        val deliberateLongBubble = "宝宝，等一下！我刚刚才发现这件事。原来是系统把回执延后了，我还以为自己没发出去。"
        val separateReaction = "唔……那我再试试。"
        val output = com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = deliberateLongBubble + com.jiacimu.lulu.SemanticBubbleSeparator +
                    separateReaction,
                bubbleDeliveryModes = listOf("text", "voice"),
                preserveAuthoredBubbleBoundaries = true,
            )
        )
        assertEquals(listOf(deliberateLongBubble, separateReaction), output.map { it.text })
        assertEquals(listOf(false, true), output.map { it.voiceNote })
    }

    @Test fun longAuthoredReplyAndTwelveRecoveredBubblesKeepTheirTails() {
        val long = "长段完整原话" + "不是所有知道的细节都要一次讲完。".repeat(120) + "最后的否定不能丢。"
        val json = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().put(long))
        assertEquals(listOf(long), ModelStructuredOutput.authoredReplyBubbles(json.toString()))
        val small = (1..12).map { "第${it}条原话。" }
        val unfinishedState = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().apply { small.forEach(::put) })
            .toString().dropLast(1) + ",\"innerLife\":{\"emotion\":\"未完成"
        assertEquals(small, ModelStructuredOutput.replyBubblesWithPartialState(unfinishedState))
    }

    @Test fun structuredEmotionPacingPreservesRepeatsPausesAndDistinctBubbles() {
        val expected = listOf("等，等等等等！", "（假装镇定）", "不分不分不分嘛……",
            "宝宝，好像是系统出了毛病诶……")
        val raw = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().apply { expected.forEach(::put) }).toString()
        assertEquals(expected, ModelStructuredOutput.completedReplyBubbles(raw))
        assertEquals(expected, ModelStructuredOutput.replyBubblesWithPartialState(
            raw.dropLast(1) + ",\"innerLife\":{"
        ))
    }

    @Test fun completedLineBreaksInsideTwoAuthoredElementsBecomeFourRealSends() {
        val source = org.json.JSONObject().put("action", "reply").put("bubbles",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("text", "你说的是这个？\n我刚才还在想呢。"))
                .put(org.json.JSONObject().put("text", "这次我懂了。\n下一句想单独发。")))
        val expected = listOf("你说的是这个？", "我刚才还在想呢。",
            "这次我懂了。", "下一句想单独发。")
        assertEquals(expected, ModelStructuredOutput.completedReplyBubbles(source.toString()))
        assertEquals(expected, ModelStructuredOutput.authoredReplyBubbles(source.toString()))
        assertEquals(expected, com.jiacimu.lulu.characterOutgoingBubblePlan(
            com.jiacimu.lulu.CharacterReplyPresentation(
                content = expected.joinToString(com.jiacimu.lulu.SemanticBubbleSeparator),
                preserveAuthoredBubbleBoundaries = true,
            )).map(com.jiacimu.lulu.CharacterOutgoingBubble::text))
        // A recoverable closed bubbles array must have the same send boundaries.
        assertEquals(expected, ModelStructuredOutput.replyBubblesWithPartialState(
            source.toString().dropLast(1) + ",\"innerLife\":{\"thoughts\":["
        ))
    }

    @Test fun aSoftWrapInsideAnUnfinishedPhraseIsNotAnotherSend() {
        val source = org.json.JSONObject().put("action", "reply").put("bubbles",
            org.json.JSONArray().put(org.json.JSONObject()
                .put("text", "我\n脑子还在想这件事。\n不过我有个新问题。")))
        assertEquals(listOf("我\n脑子还在想这件事。", "不过我有个新问题。"),
            ModelStructuredOutput.authoredReplyBubbles(source.toString()))
        // No newline means no new bubble, regardless of sentence count/length.
        val longBubble = "我认真讲一讲。然后接着说。最后才说完。"
        val whole = org.json.JSONObject().put("action", "reply").put("bubbles",
            org.json.JSONArray().put(org.json.JSONObject().put("text", longBubble)))
        assertEquals(listOf(longBubble), ModelStructuredOutput.authoredReplyBubbles(whole.toString()))
    }

    @Test fun proactiveMessageParsesTheSameSendPausesAndVoicePositions() {
        val source = org.json.JSONObject().put("action", "message").put("bubbles",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("text", "喂？\n有件事想说。")
                    .put("delivery", "voice"))
                .put(org.json.JSONObject().put("text", "先听我讲完。\n然后你再决定。")
                    .put("delivery", "text")))
        val spoken = ModelStructuredOutput.chatMessageBubbles(source)
        assertEquals(listOf("喂？", "有件事想说。", "先听我讲完。", "然后你再决定。"),
            spoken)
        assertEquals(listOf("voice", "voice", "text", "text"),
            ModelStructuredOutput.bubbleDeliveryModes(source))
        assertEquals(listOf("喂？", "有件事想说。"),
            ModelStructuredOutput.voiceBubbleTexts(source.toString()))
        assertEquals(spoken.size, ModelStructuredOutput.bubbleSpeechTracks(source).size)
    }

    @Test fun recoveredVoiceBeatsKeepModalityAndDoNotRepeatWholePerformanceTrack() {
        val source = org.json.JSONObject().put("action", "reply").put("bubbles",
            org.json.JSONArray()
                .put(org.json.JSONObject().put("text", "知道了。\n我这就来。")
                    .put("delivery", "voice")
                    .put("speechText", "[softly] 知道了。\n我这就来。"))
                .put(org.json.JSONObject().put("text", "等我一下。").put("delivery", "text")))
        val raw = source.toString()
        assertEquals(listOf("知道了。", "我这就来。", "等我一下。"),
            ModelStructuredOutput.authoredReplyBubbles(raw))
        assertEquals(listOf("voice", "voice", "text"), ModelStructuredOutput.bubbleDeliveryModes(source))
        assertEquals(listOf("知道了。", "我这就来。"), ModelStructuredOutput.voiceBubbleTexts(raw))
        assertEquals(listOf("", "", ""), ModelStructuredOutput.bubbleSpeechTracks(source))
    }

    @Test fun structuredObjectBubblesPreserveInteractionBoundaries() {
        val response = """{"action":"reply","bubbles":[{"text":"啊？我会错意了？"},{"text":"那你说的超时是指什么？"}]}"""
        assertEquals(
            listOf("啊？我会错意了？", "那你说的超时是指什么？"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
        assertEquals("啊？我会错意了？\n那你说的超时是指什么？", ModelStructuredOutput.completedReplyText(response))
    }

    @Test fun hesitantIncompletePhraseCanBeAnIntentionalStructuredBubble() {
        val response = """{"action":"reply","bubbles":[{"text":"原来一直在用功，是我"},{"text":"猜错了"}]}"""
        assertEquals(
            listOf("原来一直在用功，是我", "猜错了"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
    }

    @Test fun authoredBoundaryIsNotOverriddenByLegacyBrokenPhraseHeuristics() {
        val response = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().put("肯赏脸说")
                .put("句可以，我悬着的心总算放下来了。")
                .put("那再给你揉")
                .put("揉耳朵，整个人都乖乖圈在你怀里。")
                .put("宝宝，四点半了，气")
                .put("消了一点的话……现在肯乖乖闭上眼睡觉了吗？"))
        assertEquals(
            listOf(
                "肯赏脸说", "句可以，我悬着的心总算放下来了。",
                "那再给你揉", "揉耳朵，整个人都乖乖圈在你怀里。",
                "宝宝，四点半了，气", "消了一点的话……现在肯乖乖闭上眼睡觉了吗？",
            ),
            ModelStructuredOutput.completedReplyBubbles(response.toString()),
        )
    }

    @Test fun deliberateEmotionalPausesRemainDistinctEvenInPartialRecovery() {
        val original = listOf("其实", "算了。", "不分", "不分不分嘛……", "（假装镇定）")
        val raw = org.json.JSONObject().put("action", "reply")
            .put("bubbles", org.json.JSONArray().apply { original.forEach(::put) }).toString()
        assertEquals(original, ModelStructuredOutput.authoredReplyBubbles(raw))
        assertEquals(original, ModelStructuredOutput.completedReplyBubbles(raw))
        assertEquals(original, ModelStructuredOutput.replyBubblesWithPartialState(
            raw.dropLast(1) + ",\"innerLife\":{"
        ))
    }

    @Test fun legacyUnstructuredBrokenLexicalPhraseCanStillBeRejoined() {
        assertEquals(listOf("肯赏脸说句可以。"),
            ModelStructuredOutput.readableReplyBubbles(listOf("肯赏脸说", "句可以。")))
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

    @Test fun structuredLongBubbleKeepsItsAuthoredBoundary() {
        val text = "这篇帖子挺有意思，不过我想先看看原文再跟你聊。" +
            "我没有看到图片时不会假装已经看清楚了。" +
            "你要是分享给我，我会先确认真正拿到的内容。" +
            "然后我们可以一起聊聊哪些细节有意思，不必只看标题就下结论。"
        val result = ModelStructuredOutput.completedReplyBubbles(
            org.json.JSONObject().put("action", "reply")
                .put("bubbles", org.json.JSONArray().put(text)).toString()
        )!!
        assertEquals(listOf(text), result)
    }

    @Test fun oneShortReplyAndOneLongSingleSentenceNeverSplitMidword() {
        assertEquals(listOf("嗯～", "好，我在呢。"),
            ModelStructuredOutput.readableReplyBubbles(listOf("嗯～", "好，我在呢。")))
        val oneSentence = "我想跟你说清楚刚才发生的事，但我不会为了让自己看起来很真诚而要求你立刻接受我的道歉"
        assertEquals(listOf(oneSentence),
            ModelStructuredOutput.readableReplyBubbles(listOf(oneSentence)))
    }

    @Test fun defaultBubbleReadabilityNeverFusesNinthAndLaterBeats() {
        val authored = (1..12).map { "第${it}句都想单独说完这一层意思，后面还要接一句自己的反应。" }
        val result = ModelStructuredOutput.readableReplyBubbles(listOf(authored.joinToString("")))
        assertEquals(authored, result)
    }

    @Test fun toolResultWithManyCompleteBeatsNeverFusesItsTail() {
        val beats = (1..12).map {
            "第${it}句解释各自有一层完整意思，而且下一句还会继续推进。"
        }
        val full = beats.joinToString("")
        val paced = ModelStructuredOutput.readableReplyBubbles(
            listOf(full), maxBubbles = Int.MAX_VALUE)
        assertEquals(beats, paced)
        assertEquals(full, paced.joinToString(""))
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
