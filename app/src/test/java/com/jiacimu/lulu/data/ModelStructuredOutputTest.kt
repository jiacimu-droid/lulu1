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

    @Test fun structuredBubbleBoundariesAreOwnedByTheModel() {
        val response = """{"action":"reply","bubbles":[{"text":"原来一直在用功，是我"},{"text":"猜错了"}]}"""
        assertEquals(
            listOf("原来一直在用功，是我", "猜错了"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
    }

    @Test fun completedInteractionMovesRemainSeparateBubbles() {
        val response = """{"action":"reply","bubbles":[{"text":"好，我知道了。"},{"text":"你先歇一会儿。"}]}"""
        assertEquals(
            listOf("好，我知道了。", "你先歇一会儿。"),
            ModelStructuredOutput.completedReplyBubbles(response),
        )
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
    }

    @Test fun emotionalFollowThroughNeedsBothRegretAndRealConflict() {
        assertTrue(EmotionFollowThroughCoordinator.qualifies("对不起，我没有履行约定", "你答应十点叫我却没叫"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("对不起，刚才手滑", "午饭吃了披萨"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("晚点再看书", "你答应十点叫我却没叫"))
    }
}
