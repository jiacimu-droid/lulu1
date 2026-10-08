package com.jiacimu.lulu.study

import org.junit.Assert.*
import org.junit.Test

class TheaterProseRhythmTest {
    @Test fun repeatedStockMetaphorsAreFlaggedButPreciseLanguageIsNot() {
        val chapters = listOf(
            StarWishTheaterChapter(theater = "书", chapter = 1, title = "一", content = "他像野狗。野狗。俊美。", userInfluence = ""),
            StarWishTheaterChapter(theater = "书", chapter = 2, title = "二", content = "他又像野狗。野狗。俊美。", userInfluence = ""),
        )
        val overused = TheaterProseRhythm.overusedIn(chapters)
        assertTrue(overused.contains("野狗"))
        assertFalse(overused.contains("俊美"))
        assertTrue(TheaterProseRhythm.overusedIn(emptyList()).isEmpty())
    }
}
