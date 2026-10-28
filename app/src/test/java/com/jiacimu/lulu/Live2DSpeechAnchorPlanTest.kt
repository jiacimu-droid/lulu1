package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class Live2DSpeechAnchorPlanTest {
    @Test fun exactSentenceAnchorsSplitOnlyAtActualPhraseStarts() {
        val text = "你回来啦！我还在想你呢。不过我没有一直等。"
        val plan = Live2DSpeechAnchorPlan.build(text, listOf("你回来啦", "不过我没有", "我还在想你"))
        assertEquals(listOf("你回来啦！", "我还在想你呢。", "不过我没有一直等。"), plan.parts)
        assertEquals(0, plan.segmentForAnchor["你回来啦"])
        assertEquals(1, plan.segmentForAnchor["我还在想你"])
        assertEquals(2, plan.segmentForAnchor["不过我没有"])
        assertEquals(text, plan.parts.joinToString(""))
    }

    @Test fun missingAmbiguousAndMidWordAnchorsDoNotGuessTtsTimestamps() {
        val text = "你好，你好。欢迎回家。"
        val plan = Live2DSpeechAnchorPlan.build(text,
            listOf("你好", "好", "从未说过", "欢迎回家"))
        assertNull(plan.segmentForAnchor["你好"])
        assertNull(plan.segmentForAnchor["好"])
        assertNull(plan.segmentForAnchor["从未说过"])
        assertEquals(1, plan.segmentForAnchor["欢迎回家"])
        assertEquals(text, plan.parts.joinToString(""))
    }

    @Test fun onlyModelAuthoredAnchorsCauseExtraSynthesisSegments() {
        val text = "甲。乙。丙。丁。戊。己。"
        assertEquals(listOf(text), Live2DSpeechAnchorPlan.build(text, emptyList()).parts)
        val plan = Live2DSpeechAnchorPlan.build(text, listOf("乙", "丙", "丁", "戊", "己"))
        assertEquals(4, plan.parts.size)
        assertNull(plan.segmentForAnchor["戊"])
        assertNull(plan.segmentForAnchor["己"])
        assertEquals(text, plan.parts.joinToString(""))
    }

    @Test fun blankInputProducesNoSyntheticSpeech() {
        assertTrue(Live2DSpeechAnchorPlan.build(" ", listOf("嗨")).parts.isEmpty())
    }
}
