package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Verify online agency can truthfully stay quiet without treating it as malformed JSON. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ProactiveQuietDecisionTest {
    @Test fun validSilentChoiceIsParsedAndKeepsItsReason() {
        val parsed = parse("""{"action":"silent","reason":"今晚真的想一个人消化刚才的经历","mood":"平静"}""")
        assertNotNull(parsed)
        assertTrue(parsed.toString().contains("action=SILENT"))
        assertTrue(parsed.toString().contains("今晚真的想"))
    }

    @Test fun actualSpontaneousSharingAndJournalingStayExecutable() {
        assertTrue(parse("""{"action":"moment","text":"刚读到那句我愣住了。"}""")
            .toString().contains("action=MOMENT"))
        assertTrue(parse("""{"action":"journal","journalTitle":"今天","journalContent":"还在想那一幕"}""")
            .toString().contains("action=JOURNAL"))
    }

    @Test fun invalidUnknownActionIsNotSilentlySwallowed() {
        assertNull(parse("""{"action":"invented_human_action","reason":"这是不存在的工具"}"""))
    }

    @Test fun modelChosenAutonomousBubblesSurviveTheDecisionAndSender() {
        val decision = parse("""{"action":"message","bubbles":["辛苦啦宝宝，先歇一会儿。","那张震惊猫猫头把我逗笑了。"]}""")!!
        val field = decision.javaClass.getDeclaredField("text").apply { isAccessible = true }
        val spoken = field.get(decision) as String
        assertTrue(spoken.contains(com.jiacimu.lulu.SemanticBubbleSeparator))
        assertEquals(listOf("辛苦啦宝宝，先歇一会儿。", "那张震惊猫猫头把我逗笑了。"),
            com.jiacimu.lulu.semanticReplyBubbles(spoken))
    }

    @Test fun partialAutonomousJsonKeepsCompletedBubblesWithoutInventingAnotherReply() {
        val incomplete = """{"action":"message","bubbles":["刚学完？","过来让我抱抱。"],"innerLife":{"unfinished":"""
        val recovered = AutonomousDecisionRecovery.completedPrivateMessage(incomplete)
        assertNotNull(recovered)
        assertEquals(listOf("刚学完？", "过来让我抱抱。"),
            com.jiacimu.lulu.semanticReplyBubbles(recovered!!.optString("text")))
    }

    private fun parse(raw: String): Any? {
        val method = ProactivePerceptionRuntime::class.java.declaredMethods.first {
            it.name == "parseDecision" && it.parameterCount == 2
        }
        method.isAccessible = true
        return method.invoke(ProactivePerceptionRuntime, raw, false)
    }
}
