package com.jiacimu.lulu.study

import org.junit.Assert.*
import org.junit.Test

class TheaterChapterCompletionTest {
    @Test fun aPunctuatedEndWithoutMarkerIsNotMistakenForCompletedChapter() {
        val body = "她推开门，所有人都安静了。"
        assertFalse(TheaterChapterCompletion.isFinished(body, "stop"))
        assertFalse(TheaterChapterCompletion.isFinished("她刚要说，", "length"))
        assertTrue(TheaterChapterCompletion.cutOffByTokens("max_tokens"))
        assertTrue(TheaterChapterCompletion.cutOffByTokens("length"))
        assertFalse(TheaterChapterCompletion.cutOffByTokens("stop"))
    }

    @Test fun markerIsHiddenButDoesNotCutNarrativeBeforeIt() {
        val original = "所有人终于低下了头。\n【本章正文结束】"
        assertTrue(TheaterChapterCompletion.isFinished(original, "stop"))
        assertEquals("所有人终于低下了头。", TheaterChapterCompletion.clean(original))
    }

    @Test fun unfinishedSentenceIsStitchedWithoutLosingItsEnd() {
        val first = "她伸出手，轻轻按住"
        val next = "按住他颤抖的手腕，低声道：“别怕。”【本章正文结束】"
        val merged = TheaterChapterCompletion.append(first, next)
        assertEquals("她伸出手，轻轻按住他颤抖的手腕，低声道：“别怕。”", TheaterChapterCompletion.clean(merged))
        assertTrue(TheaterChapterCompletion.isFinished(merged, "stop"))
    }

    @Test fun duplicatedTailIsRemovedButLegitimateRepeatedDialogueIsPreserved() {
        val first = "你看向她。\n她说：“不准走。”\n他却笑了笑"
        val next = "他却笑了笑，转身关上了门。\n\n她又说：“不准走。”【本章正文结束】"
        val merged = TheaterChapterCompletion.append(first, next)
        assertEquals(1, "他却笑了笑".toRegex().findAll(merged).count())
        assertEquals(2, "不准走".toRegex().findAll(merged).count())
    }

    @Test fun completeParagraphsGetNaturalSpacing() {
        val merged = TheaterChapterCompletion.append("他推门而入。", "众人同时站了起来。【本章正文结束】")
        assertEquals("他推门而入。\n\n众人同时站了起来。", TheaterChapterCompletion.clean(merged))
    }
}
