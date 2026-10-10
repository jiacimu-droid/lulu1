package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class AutonomousDecisionRecoveryTest {
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

    @Test fun aNormalChosenActionIsNeverReplaced() {
        val original = JSONObject().put("action", "moment").put("text", "今日小事")
        assertSame(original, AutonomousDecisionRecovery.choose(original))
    }
}
