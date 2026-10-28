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
        assertEquals((0..47).toSet(), Live2DMotionCatalog.entries.keys)
        assertEquals(48, Live2DMotionCatalog.entries.values.distinctBy { it.name }.size)
    }

    @Test fun everyEntryHasUsableNameFitsAndRealReferences() {
        for ((_, entry) in Live2DMotionCatalog.entries) {
            assertTrue(entry.name.isNotBlank())
            assertTrue(entry.fits.isNotBlank())
            // Same emotion, different clips: the fits text must say what the
            // body actually does, or the role cannot tell the clips apart.
            assertTrue(entry.fits.contains("动作是"))
            entry.expression?.let { assertTrue(it in realExpressions) }
            entry.touch?.let { assertTrue(it in realTouches) }
        }
    }

    @Test fun describeNamesWolfMotionsAndPassesOthersThrough() {
        assertEquals(
            "LuluWolf#19=咯咯笑晃动（忍不住笑、咯咯笑时；动作是头小幅度快速左右晃，像憋不住笑在抖；神态：笑眯眯、眯起眼）",
            Live2DMotionCatalog.describe("LuluWolf", 19),
        )
        assertEquals(
            "LuluWolf#20=难过垂头（难过、低落、叹气时；动作是头慢慢垂下去停住，半天才抬回来，幅度深；配流泪；神态：眉眼低垂、嘴角下压）",
            Live2DMotionCatalog.describe("LuluWolf", 20),
        )
        assertNull(Live2DMotionCatalog.describe("LuluWolf", 99))
        assertNull(Live2DMotionCatalog.describe("Lulu", 0))
    }

    @Test fun facePresetsOnlyUseRealChannelsInRangeAndMatchTheirNotes() {
        val positive = setOf("eyeOpen", "eyeSmile", "squint", "cheekPuff", "pout")
        val channels = Live2DVisualActionBus.faceChannels.keys
        for ((_, entry) in Live2DMotionCatalog.entries) {
            assertEquals(entry.face.isNotEmpty(), entry.faceNote != null)
            for ((key, value) in entry.face) {
                assertTrue(key in channels)
                val min = if (key in positive) 0f else -1f
                assertTrue(value in min..1f)
            }
        }
    }
}
