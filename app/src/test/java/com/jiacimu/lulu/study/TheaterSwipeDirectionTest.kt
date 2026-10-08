package com.jiacimu.lulu.study

import org.junit.Assert.assertEquals
import org.junit.Test

class TheaterSwipeDirectionTest {
    @Test fun swipingLeftGoesToNextChapter() {
        assertEquals(2, theaterChapterIndexAfterSwipe(1, 5, -100f, 72f))
        assertEquals(4, theaterChapterIndexAfterSwipe(4, 5, -100f, 72f))
    }

    @Test fun swipingRightGoesToPreviousChapter() {
        assertEquals(0, theaterChapterIndexAfterSwipe(1, 5, 100f, 72f))
        assertEquals(0, theaterChapterIndexAfterSwipe(0, 5, 100f, 72f))
    }

    @Test fun shortDragAndEmptyBookshelfDoNotChangeChapter() {
        assertEquals(2, theaterChapterIndexAfterSwipe(2, 5, -40f, 72f))
        assertEquals(2, theaterChapterIndexAfterSwipe(2, 5, 40f, 72f))
        assertEquals(0, theaterChapterIndexAfterSwipe(0, 0, -100f, 72f))
    }
}
