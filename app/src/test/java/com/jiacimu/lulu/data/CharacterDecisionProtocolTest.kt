package com.jiacimu.lulu.data

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class CharacterDecisionProtocolTest {
    @Test fun quietDecisionIsValidWithoutSpeech() {
        val quiet = JSONObject("""{"action":"silent","reason":"我现在需要自己想想"}""")
        assertEquals("silent", CharacterDecisionProtocol.chatAction(quiet))
        assertNull(ModelStructuredOutput.completedReplyText(quiet.toString()))
    }

    @Test fun aReplyCanBeDecidedBeforeItsWording() {
        val plan = JSONObject("""{"action":"reply","speechIntent":"反过来问她为什么这样想"}""")
        assertEquals("reply", CharacterDecisionProtocol.chatAction(plan))
        assertEquals("反过来问她为什么这样想", CharacterDecisionProtocol.speechIntent(plan))
        assertTrue(CharacterDecisionProtocol.usesSeparateExpression("正在私聊"))
        assertFalse(CharacterDecisionProtocol.usesSeparateExpression("正在电话中"))
    }

    @Test fun aGroupIsQuietOnlyWhenSilenceIsExplicitlyChosen() {
        assertTrue(CharacterDecisionProtocol.groupIsExplicitlySilent("""{"action":"silent","reason":"大家都在忙","turns":[]}"""))
        assertFalse(CharacterDecisionProtocol.groupIsExplicitlySilent("""{"turns":[]}"""))
        assertFalse(CharacterDecisionProtocol.groupIsExplicitlySilent("""{"action":"silent","turns":[{"characterId":"a"}]}"""))
    }

    @Test fun invalidAndUnknownToolsAreNotValidChoices() {
        assertNull(CharacterDecisionProtocol.chatAction(JSONObject("""{"action":"tool","text":"已经完成"}""")))
        assertNull(CharacterDecisionProtocol.chatAction(JSONObject("""{"action":"delete_everything","text":"完成"}""")))
        assertEquals("tool", CharacterDecisionProtocol.chatAction(JSONObject("""{"action":"tool","tool":"get_battery","args":{}}""")))
    }
}
