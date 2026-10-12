package com.jiacimu.lulu.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ReadingReactionPolicyTest {
    @Test fun shortExclamationAndNoFeelingAreBothValid() {
        assertEquals("卧槽。神了！", ReadingReactionPolicy.parse(
            ReadingReactionPolicy.responseObject("卧槽。神了！")!!, "原文", 0).reflection)
        val silent = ReadingReactionPolicy.parse(JSONObject(), "原文", 50)
        assertTrue(silent.moments.isEmpty()); assertEquals("", silent.reflection)
        assertEquals("", silent.innerThought)
        assertNull(ReadingReactionPolicy.responseObject("{\"reflection\":\"还没写完"))
    }
    @Test fun reactionsPointToTheActualReadLocationAndInventedQuotesAreExcluded() {
        val text = "他打开门。原来是猫！后来他笑了。"
        val response = JSONObject().put("moments", JSONArray()
            .put(JSONObject().put("quote", "原来是猫！").put("reaction", "这也太会藏了吧。"))
            .put(JSONObject().put("quote", "猫变成了龙").put("reaction", "神了")))
        val moment = ReadingReactionPolicy.parse(response, text, 120).moments.single()
        assertEquals(120 + text.indexOf("原来是猫！"), moment.startOffset)
        assertEquals(moment.quote, text.substring(moment.startOffset - 120, moment.endOffset - 120))
    }
    @Test fun repeatedLinesCanSelectTheSecondActualOccurrence() {
        val text = "别走。她说。别走。他说。"
        val response = JSONObject().put("moments", JSONArray().put(JSONObject()
            .put("quote", "别走。").put("reaction", "这次换他不舍得了。")
            .put("occurrence", 2)))
        assertEquals(text.lastIndexOf("别走。"), ReadingReactionPolicy.parse(response, text, 0).moments.single().startOffset)
        response.getJSONArray("moments").getJSONObject(0).put("occurrence", 3)
        assertTrue(ReadingReactionPolicy.parse(response, text, 0).moments.isEmpty())
    }
    @Test fun momentsSurviveThePersistenceCodecWithoutTurningIntoAFormalReview() {
        val moment = ReadingMoment("门开了。", "卧槽。", 25, 29, "吓了一跳")
        assertEquals(listOf(moment), ReadingReactionPolicy.decodeMoments(ReadingReactionPolicy.encodeMoments(listOf(moment))))
        assertTrue(ReadingReactionPolicy.decodeMoments(null).isEmpty())
    }
}
