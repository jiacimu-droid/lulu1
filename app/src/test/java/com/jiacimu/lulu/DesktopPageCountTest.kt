package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopPageCountTest {
    @Test fun smallDesktopStillHasSecondPage() {
        assertEquals(2, desktopAllocatedPageCount(0))
        assertEquals(2, desktopAllocatedPageCount(15))
        assertEquals(2, desktopAllocatedPageCount(16))
    }

    @Test fun addingAppsCreatesAnotherBlankPage() {
        assertEquals(3, desktopAllocatedPageCount(17))
        assertEquals(3, desktopAllocatedPageCount(32))
        assertEquals(4, desktopAllocatedPageCount(33))
    }
}
