package com.jiacimu.lulu.data

import org.junit.Assert.*
import org.junit.Test

class XhsNoteReadinessTest {
    @Test fun shortCaptionDoesNotCompleteBeforeTheCarouselArrives() {
        val gate = XhsNoteReadiness()
        assertFalse(gate.ready("先出现的20字短正文", emptyList(), 0, 0))
        assertFalse(gate.ready("先出现的20字短正文", emptyList(), 0, 700))
        assertFalse(gate.ready("完整图文正文", listOf("first", "second"), 2, 1_400))
        assertFalse(gate.ready("完整图文正文", listOf("first", "second"), 2, 2_100))
        assertTrue(gate.ready("完整图文正文", listOf("first", "second"), 2, 2_450))
    }

    @Test fun newTextAndAdditionalImagesRestartTheSettlingWindow() {
        val gate = XhsNoteReadiness()
        assertFalse(gate.ready("正文", listOf("first"), 1, 0))
        assertFalse(gate.ready("完整正文", listOf("first", "second"), 2, 1_000))
        assertFalse(gate.ready("完整正文", listOf("first", "second"), 2, 2_000))
        assertTrue(gate.ready("完整正文", listOf("first", "second"), 2, 2_050))
    }

    @Test fun textOnlyAndPartialNotesEventuallyFinishWithoutInventingMedia() {
        val gate = XhsNoteReadiness()
        assertFalse(gate.ready("纯文字", emptyList(), 0, 0))
        assertTrue(gate.ready("纯文字", emptyList(), 0, 4_500))
        assertFalse(gate.ready("图文", listOf("one-of-three"), 3, 5_000))
        assertFalse(gate.ready("图文", listOf("one-of-three"), 3, 6_100))
        assertTrue(gate.ready("图文", listOf("one-of-three"), 3, 9_500))
    }

    @Test fun missingContentNeverCountsAsACompletedRead() {
        val gate = XhsNoteReadiness()
        assertFalse(gate.ready("", emptyList(), 0, 0))
        assertFalse(gate.ready("", emptyList(), 0, 100_000))
    }
}
