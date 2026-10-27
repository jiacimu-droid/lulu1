package com.jiacimu.lulu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Live2DMotionCatalogTest {
    private val realExpressions = setOf("兽耳", "吐舌", "星星眼", "流泪", "爱心眼", "耳机", "脸红", "脸黑", "麦")
    private val realTouches = setOf(
        "beast-ear:stroke", "hair:stroke", "cheek:stroke", "cheek:slap",
        "nose:tap", "forehead:stroke", "chest:tap",
    )

    @Test fun catalogCoversWholeWolfPackExactlyOnce() {
        assertEquals((0..29).toSet(), Live2DMotionCatalog.entries.keys)
        assertEquals(30, Live2DMotionCatalog.entries.values.distinctBy { it.name }.size)
    }

    @Test fun everyEntryHasUsableNameFitsAndRealReferences() {
        for ((_, entry) in Live2DMotionCatalog.entries) {
            assertTrue(entry.name.isNotBlank())
            assertTrue(entry.fits.isNotBlank())
            entry.expression?.let { assertTrue(it in realExpressions) }
            entry.touch?.let { assertTrue(it in realTouches) }
        }
    }

    @Test fun describeNamesWolfMotionsAndPassesOthersThrough() {
        assertEquals(
            "LuluWolf#19=咯咯笑晃动（忍不住笑、咯咯笑时）",
            Live2DMotionCatalog.describe("LuluWolf", 19),
        )
        assertEquals(
            "LuluWolf#20=难过垂头（难过、低落、叹气时；配流泪）",
            Live2DMotionCatalog.describe("LuluWolf", 20),
        )
        assertNull(Live2DMotionCatalog.describe("LuluWolf", 99))
        assertNull(Live2DMotionCatalog.describe("Lulu", 0))
    }
}
