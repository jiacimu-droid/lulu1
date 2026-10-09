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

    @Test fun briefPrefixWithNoObjectFailsSafely() {
        assertNull(ModelStructuredOutput.objectOrNull("正在思考，随后回复。"))
    }

    @Test fun emotionalFollowThroughNeedsBothRegretAndRealConflict() {
        assertTrue(EmotionFollowThroughCoordinator.qualifies("对不起，我没有履行约定", "你答应十点叫我却没叫"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("对不起，刚才手滑", "午饭吃了披萨"))
        assertFalse(EmotionFollowThroughCoordinator.qualifies("晚点再看书", "你答应十点叫我却没叫"))
    }
}
