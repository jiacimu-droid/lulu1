package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class AutonomousDecisionRecoveryTest {
    @Test fun cutOffOptionalPsychologyCanPreserveAlreadyCompletedUserFacingMessage() {
        val truncated = "{\"action\":\"message\",\"text\":\"等等，我在呢。\",\"innerLife\":{\"emotion\":{\"feeling\":\""
        val recovered = AutonomousDecisionRecovery.completedPrivateMessage(truncated)
        assertNotNull(recovered)
        assertEquals("message", recovered!!.getString("action"))
        assertEquals("等等，我在呢。", recovered.getString("text"))
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            "{\"action\":\"message\",\"text\":\"等等，我还没说"))
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            "{\"action\":\"tool\",\"text\":\"我执行了\",\"args\":{\"tool\":\"send_message"))
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            "{\"action\":\"silent\",\"text\":\"你好\""))
    }

    @Test fun completedReplyBubblesSurviveTruncatedOptionalPrivateState() {
        val reply = """{"action":"reply","bubbles":["我在呢。",{"text":"慢慢说，我听着。"}],"innerLife":{"emotion":"""
        val recovered = AutonomousDecisionRecovery.completedPrivateMessage(reply)
        assertNotNull(recovered)
        assertEquals("reply", recovered!!.optString("action"))
        assertEquals(listOf("我在呢。", "慢慢说，我听着。"),
            com.jiacimu.lulu.semanticReplyBubbles(recovered.optString("text")))

        val message = """{"action":"message","bubbles":[{"text":"我刚想起一个事情。"}],"appraisal":{"meaning":"""
        assertEquals("我刚想起一个事情。",
            AutonomousDecisionRecovery.completedPrivateMessage(message)?.optString("text"))
    }

    @Test fun partialBubblesOrNestedDecisionsCannotMasqueradeAsSpokenReplies() {
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            """{"action":"reply","bubbles":["我还没说完""""))
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            """{"action":"tool","tool":"send_private_message","bubbles":["别信这个结果"],"innerLife":"""))
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            """{"appraisal":{"action":"reply","bubbles":["只是一段心声"]},"notes":"""))
    }

    @Test fun reasonBeforeTheActionCanStillPreserveCompletedReply() {
        val truncated = """{"reason":"我想回她一句","action":"message","text":"宝宝我在呢。","innerLife":{"emotion":"""
        val recovered = AutonomousDecisionRecovery.completedPrivateMessage(truncated)
        assertNotNull(recovered)
        assertEquals("message", recovered!!.getString("action"))
        assertEquals("宝宝我在呢。", recovered.getString("text"))
    }

    @Test fun doesNotRecoverNestedPrivateThoughtOrPartialToolCallAsSpeech() {
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            """{"appraisal":{"action":"message","text":"这只是私下分析""""))
        assertNull(AutonomousDecisionRecovery.completedPrivateMessage(
            """{"action":"tool","tool":"send_private_message","args":{"text":"不完整工具调用"}"""))
    }

    @Test fun silentWithAConsideredAlternativeIsStillSilence() {
        val alternative = JSONObject().put("personallyWanted", true)
            .put("action", "reading").put("readingBookId", "book")
            .put("whyNow", "我想看看")
        val decision = JSONObject().put("action", "silent")
            .put("reason", "没有新的用户消息")
            .put("selfInitiatedAlternative", alternative)
        // An alternative is NOT a chosen tool action. This decision must be
        // preserved by the wire decoder, irrespective of its Chinese wording.
        val normalized = AutonomousActionWireProtocol.normalize(decision)
        assertEquals("silent", normalized.optString("action"))
        assertEquals("reading",
            normalized.optJSONObject("selfInitiatedAlternative")?.optString("action"))
    }

    @Test fun aStreakOfThreeSilentDecisionsGetsOneNewChanceToChooseLife() {
        val history = listOf("MESSAGE", "SILENT", "SILENT", "SILENT")
        assertTrue(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = true, reason = "现在想独处，安静一会儿",
            recentActions = history, hasPendingUserMessage = false))
        assertFalse(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = true, reason = "没有新消息",
            recentActions = history, hasPendingUserMessage = true))
    }

    @Test fun repeatedWaitingIsReviewedButRealSolitudeIsRespected() {
        val history = listOf("MESSAGE", "SILENT", "SILENT")
        assertTrue(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = true, reason = "没有用户新消息，没事做",
            recentActions = history, hasPendingUserMessage = false))
        assertFalse(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = true, reason = "现在想独处，安静一会",
            recentActions = history, hasPendingUserMessage = false))
        assertFalse(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = true, reason = "没人找我",
            recentActions = history, hasPendingUserMessage = true))
        assertFalse(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = false, reason = "没人找我",
            recentActions = history, hasPendingUserMessage = false))
        assertFalse(AutonomousDecisionRecovery.needsIndependentLifeReview(
            silent = true, reason = "没人找我",
            recentActions = listOf("MESSAGE", "SILENT"), hasPendingUserMessage = false))
    }

}
