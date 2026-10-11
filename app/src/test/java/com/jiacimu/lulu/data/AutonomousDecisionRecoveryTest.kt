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

    @Test fun aNormalChosenActionIsNeverReplaced() {
        val original = JSONObject().put("action", "moment").put("text", "今日小事")
        assertSame(original, AutonomousDecisionRecovery.choose(original))
    }
}
