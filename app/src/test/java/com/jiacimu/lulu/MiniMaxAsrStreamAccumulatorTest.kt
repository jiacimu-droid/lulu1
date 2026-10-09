package com.jiacimu.lulu

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class MiniMaxAsrStreamAccumulatorTest {
    @Test fun incrementalChunksPreserveActualRepeatedLaughter() {
        val a = MiniMaxAsrStreamAccumulator()
        assertEquals("哈", a.accept(JSONObject().put("delta", "哈")))
        assertEquals("哈哈", a.accept(JSONObject().put("delta", "哈")))
        assertEquals("哈哈哈", a.accept(JSONObject().put("delta", "哈")))
        assertEquals("哈哈哈基米", a.accept(JSONObject().put("delta", "基米")))
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
