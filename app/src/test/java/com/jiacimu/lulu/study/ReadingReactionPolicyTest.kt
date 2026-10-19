package com.jiacimu.lulu.study

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
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
    @Test fun manyDifferentHighlightedSentencesAreKeptWithoutInventingQuotesOrRepeats() {
        val lines = (1..9).map { "第${it}次敲门。" }
        val source = lines.joinToString("")
        val proposed = JSONArray()
        lines.forEachIndexed { index, quote ->
            proposed.put(JSONObject().put("quote", quote).put("reaction", "我对第${index + 1}句有点想法"))
        }
        // Do not waste every entry on the same sentence; eight honest anchors fit.
        proposed.put(JSONObject().put("quote", lines[0]).put("reaction", "重复的同一句"))
        proposed.put(JSONObject().put("quote", "从未出现的句子。").put("reaction", "不应保存"))
        val result = ReadingReactionPolicy.parse(JSONObject().put("moments", proposed), source, 100)
        assertEquals(8, result.moments.size)
        assertEquals(8, result.moments.map { it.startOffset }.toSet().size)
        result.moments.forEach { moment ->
            assertEquals(moment.quote, source.substring(moment.startOffset - 100, moment.endOffset - 100))
        }
    }

    @Test fun momentsSurviveThePersistenceCodecWithoutTurningIntoAFormalReview() {
        val moment = ReadingMoment("门开了。", "卧槽。", 25, 29, "吓了一跳")
        assertEquals(listOf(moment), ReadingReactionPolicy.decodeMoments(ReadingReactionPolicy.encodeMoments(listOf(moment))))
        assertTrue(ReadingReactionPolicy.decodeMoments(null).isEmpty())
    }
}
