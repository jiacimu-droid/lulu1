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

    @Test fun aBriefActualMotiveCanSelectConcreteBookWithoutInventingAnAction() {
        val alternative = JSONObject().put("action", "reading")
            .put("readingBookId", "real-book").put("personallyWanted", true)
            .put("whyNow", "想读")
        val original = JSONObject().put("action", "silent").put("reason", "没人找我")
            .put("selfInitiatedAlternative", alternative)
        assertEquals("reading", AutonomousDecisionRecovery.choose(original).optString("action"))
        alternative.put("whyNow", "...")
        assertSame(original, AutonomousDecisionRecovery.choose(original))
    }

    @Test fun aConcreteChosenAlternativeDoesNotRequireNoMessagesExcuse() {
        val original = JSONObject().put("action", "silent").put("reason", "我还想看看书里那条线索")
            .put("selfInitiatedAlternative", JSONObject().put("action", "reading")
                .put("readingBookId", "book-1").put("personallyWanted", true)
                .put("whyNow", "想弄清上一段没有解开的疑问"))
        assertEquals("reading", AutonomousDecisionRecovery.choose(original).getString("action"))
    }

    @Test fun anIndependentActionDoesNotEraseTheSameTurnsChoiceToStayAwake() {
        val choice = JSONObject().put("choice", "continue")
            .put("reason", "这件事还没有认真说完，我自己想再留一会儿")
        val original = JSONObject().put("action", "silent").put("reason", "没有用户新消息")
            .put("onlineDecision", choice)
            .put("selfInitiatedAlternative", JSONObject().put("action", "reading")
                .put("readingBookId", "book-3").put("personallyWanted", true)
                .put("whyNow", "还想趁清醒把刚才的书读完"))
        val reviewed = AutonomousDecisionRecovery.choose(original)
        assertEquals("reading", reviewed.getString("action"))
        assertEquals("continue", reviewed.getJSONObject("onlineDecision").getString("choice"))
        assertEquals(choice.getString("reason"),
            reviewed.getJSONObject("onlineDecision").getString("reason"))
    }

    @Test fun neverOverridesARealWishToBeAlone() {
        val original = JSONObject().put("action", "silent").put("reason", "现在想好好休息")
            .put("selfInitiatedAlternative", JSONObject().put("action", "reading")
                .put("readingBookId", "book-1").put("personallyWanted", true)
                .put("whyNow", "刚才那一段很想继续读"))
        assertSame(original, AutonomousDecisionRecovery.choose(original))
    }

    @Test fun canRecoverAnExplicitIndependentChoiceFromNoMessagesHabit() {
        val alternative = JSONObject().put("action", "reading")
            .put("readingBookId", "book-1").put("personallyWanted", true)
            .put("whyNow", "我刚读到关键的一章所以想继续")
        val original = JSONObject().put("action", "silent").put("reason", "没有用户新消息")
            .put("selfInitiatedAlternative", alternative)
        val chosen = AutonomousDecisionRecovery.choose(original)
        assertEquals("reading", chosen.getString("action"))
        assertEquals("book-1", chosen.getString("readingBookId"))
    }

    @Test fun noSocialSpamAndNoVagueAlternatives() {
        val alternative = JSONObject().put("action", "message").put("text", "在吗")
            .put("personallyWanted", true).put("whyNow", "想找个人聊天")
        val original = JSONObject().put("action", "silent").put("reason", "没人联系")
            .put("selfInitiatedAlternative", alternative)
        assertSame(original, AutonomousDecisionRecovery.choose(original))
        alternative.put("action", "digital_world")
        assertSame(original, AutonomousDecisionRecovery.choose(original))
    }

    @Test fun preservedCuriosityConnectsRecoveredActionToTheSamePersonalQuestion() {
        val question = JSONObject().put("topic", "小说").put("question", "故事怎么发展")
        val original = JSONObject().put("action", "silent").put("reason", "没有用户新消息")
            .put("curiosity", question)
            .put("selfInitiatedAlternative", JSONObject().put("action", "reading")
                .put("readingBookId", "book-7").put("personallyWanted", true)
                .put("whyNow", "想去看看我还没读完的小说"))
        val chosen = AutonomousDecisionRecovery.choose(original)
        assertEquals("reading", chosen.getString("action"))
        assertEquals("小说", chosen.getJSONObject("curiosity").getString("topic"))
    }

    @Test fun recoveredIndependentActionPreservesPendingRelationalThought() {
        val thought = JSONObject().put("status", "considering")
            .put("aim", "以后找机会给她一个小惊喜").put("why", "我想分享自己真正发现的趣事")
        val original = JSONObject().put("action", "silent").put("reason", "没有用户新消息")
            .put("relationshipInitiative", thought)
            .put("selfInitiatedAlternative", JSONObject().put("action", "reading")
                .put("readingBookId", "book-surprise").put("personallyWanted", true)
                .put("whyNow", "想先自己读读这本还没看完的书"))
        val chosen = AutonomousDecisionRecovery.choose(original)
        assertEquals("reading", chosen.getString("action"))
        assertEquals("以后找机会给她一个小惊喜",
            chosen.getJSONObject("relationshipInitiative").getString("aim"))
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

    @Test fun aNormalChosenActionIsNeverReplaced() {
        val original = JSONObject().put("action", "moment").put("text", "今日小事")
        assertSame(original, AutonomousDecisionRecovery.choose(original))
    }
}
