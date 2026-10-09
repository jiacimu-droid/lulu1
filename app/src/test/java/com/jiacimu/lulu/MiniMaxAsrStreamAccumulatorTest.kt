package com.jiacimu.lulu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class MiniMaxAsrStreamAccumulatorTest {
    @Test fun incrementalChunksCanIncludeOverlappingWordsWithoutRepeats() {
        val a = MiniMaxAsrStreamAccumulator()
        assertEquals("哈基", a.accept(JSONObject().put("delta", "哈基")))
        assertEquals("哈基米", a.accept(JSONObject().put("delta", "基米")))
        assertEquals("哈基米", a.accept(JSONObject().put("delta", "基米")))
    }

    @Test fun fullHypothesisCanCorrectEarlierStreamingResult() {
        val a = MiniMaxAsrStreamAccumulator()
        a.accept(JSONObject().put("delta", "哈鸡"))
        assertEquals("哈基米", a.accept(JSONObject().put("text", "哈基米")))
        assertEquals("哈基米呀", a.accept(JSONObject().put("delta", "呀")))
    }

    @Test fun nestedResponseTextIsAcceptedAndBlankEventLeavesText() {
        val a = MiniMaxAsrStreamAccumulator()
        assertEquals("你说什么", a.accept(JSONObject().put("data", JSONObject().put("text", "你说什么"))))
        assertEquals("你说什么", a.accept(JSONObject().put("event", "done")))
    }
}
