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

    @Test fun punctuationDriftBetweenAnchorAndSpeechStillBinds() {
        val text = "不过，我可没一直等你。其实我一直在这儿。"
        val plan = Live2DSpeechAnchorPlan.build(text, listOf("不过我可没一直等你", "其实我一直在这儿"))
        assertEquals(0, plan.segmentForAnchor["不过我可没一直等你"])
        assertEquals(1, plan.segmentForAnchor["其实我一直在这儿"])
        assertEquals(text, plan.parts.joinToString(""))
    }

    @Test fun fallbackStillRefusesAmbiguousAndMidClauseAnchors() {
        val text = "走吧。走吧！我还在想你呢。"
        val plan = Live2DSpeechAnchorPlan.build(text, listOf("走吧", "还在想你"))
        assertNull(plan.segmentForAnchor["走吧"])
        assertNull(plan.segmentForAnchor["还在想你"])
        assertEquals(text, plan.parts.joinToString(""))
    }

    @Test fun existingAudioFragmentsPreserveOnlyActuallyAlignedMotionAnchors() {
        val parts = listOf("你回来啦！", "不过，我可没一直等你。", "其实呢，今天还有件事。")
        val matched = Live2DSpeechAnchorPlan.bindExistingSegments(parts,
            listOf("你回来啦", "不过我可没一直等你", "今天还有件事", "不存在"))
        assertEquals(mapOf("你回来啦" to 0, "不过我可没一直等你" to 1), matched)
        // The audio queue remains exactly the one it was before animation.
        assertEquals("你回来啦！", parts[0])
        assertEquals(3, parts.size)
    }

    @Test fun repeatedAndMidFragmentAnchorsNeverGuessAMotionTimestamp() {
        val parts = listOf("你好。然后再见。", "你好！", "下次见。")
        val matched = Live2DSpeechAnchorPlan.bindExistingSegments(parts,
            listOf("你好", "然后再见", "下次见"))
        assertNull(matched["你好"])
        assertNull(matched["然后再见"])
        assertEquals(2, matched["下次见"])
    }

    @Test fun blankInputProducesNoSyntheticSpeech() {
        assertTrue(Live2DSpeechAnchorPlan.build(" ", listOf("嗨")).parts.isEmpty())
    }
}
