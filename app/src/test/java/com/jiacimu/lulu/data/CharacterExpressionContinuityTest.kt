package com.jiacimu.lulu.data

import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterExpressionContinuityTest {
    private val at = Instant.parse("2026-10-11T13:00:00Z")

    @Test fun actualStickerDeliveryPersistsAndDoesNotDuplicate() {
        val first = CharacterExpressionContinuity.record(null, "msg-1", "图片表情", "黄猫嘟嘴亲亲", at)
        assertEquals(1, first.length())
        assertEquals(1, CharacterExpressionContinuity.record(first, "msg-1", "图片表情",
            "黄猫嘟嘴亲亲", at).length())
        val next = CharacterExpressionContinuity.record(first, "msg-2", "颜文字", "(｡•̀ᴗ-)✧", at.plusSeconds(30))
        val prompt = CharacterExpressionContinuity.context(next, at.plusSeconds(90))
        assertTrue(prompt.contains("黄猫嘟嘴亲亲"))
        assertTrue(prompt.contains("颜文字"))
        assertTrue(prompt.contains("本人社交习惯"))
    }

    @Test fun aFullHistoryKeepsTheLastSixteenDeliveredExpressions() {
        var history = JSONArray()
        for (n in 0 until 20) {
            history = CharacterExpressionContinuity.record(history,
                "msg-$n", "图片表情", "贴图$n", at.plusSeconds(n.toLong()))
        }
        assertEquals(16, history.length())
        assertEquals("msg-4", history.getJSONObject(0).getString("id"))
        assertEquals("msg-19", history.getJSONObject(15).getString("id"))
    }

    @Test fun expressionHistoryDoesNotDemandFixedStickerFrequency() {
        val guide = CharacterExpressionContinuity.guide()
        assertTrue(guide.contains("颜文字"))
        assertTrue(guide.contains("表情"))
        assertTrue(guide.contains("不是强制"))
        assertNotNull(CharacterExpressionContinuity.classifyText("嘿嘿(￣▽￣)"))
        assertNull(CharacterExpressionContinuity.classifyText("今天不想发表情"))
    }

    @Test fun historyMustNotOutliveThreeDays() {
        val history = CharacterExpressionContinuity.record(null, "old", "颜文字", "很久以前的表情", at)
        assertEquals("", CharacterExpressionContinuity.context(history, at.plusSeconds(4L * 86400)))
    }
}
