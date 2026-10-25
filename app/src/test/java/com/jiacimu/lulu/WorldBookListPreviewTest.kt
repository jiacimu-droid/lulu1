package com.jiacimu.lulu

import org.junit.Assert.*
import org.junit.Test

class WorldBookListPreviewTest {
    @Test fun scrollingPreviewIsBoundedWithoutRewritingPersistedWorldBookBody() {
        val original = "首行\n第二行\r第三行" + "很长的世界设定".repeat(60_000)
        val preview = worldBookListPreview(original)
        assertEquals(original.take(240).replace('\n', ' ').replace('\r', ' '), preview)
        assertEquals(240, preview.length)
        assertTrue(original.contains("\n"))
        assertTrue(original.length > 100_000)
    }

    @Test fun shortAndEmptyWorldBooksPreservePreviewMeaning() {
        assertEquals("", worldBookListPreview(""))
        assertEquals("有话 但只有一行", worldBookListPreview("有话\n但只有一行"))
    }
}
