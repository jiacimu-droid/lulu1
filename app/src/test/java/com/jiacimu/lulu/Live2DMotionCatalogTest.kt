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
        assertEquals((0..48).toSet(), Live2DMotionCatalog.entries.keys)
        assertEquals(49, Live2DMotionCatalog.entries.values.distinctBy { it.name }.size)
    }

    @Test fun kissIsAnExplicitCharacterSelectableWolfMotion() {
        val kiss = Live2DMotionCatalog.entries[48]
        assertEquals("贴屏亲亲", kiss?.name)
        assertTrue(kiss?.fits?.contains("镜头") == true)
        assertTrue((kiss?.face?.get("pout") ?: 0f) > 0.8f)
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
            "LuluWolf#19=咯咯笑轻颤（被逗笑、忍不住噗嗤笑时；动作是下巴轻轻点动几下，肩膀慢半拍跟着细颤，越笑越轻，头不机械地左右摇；神态：笑眯眯、忍笑时眼角轻弯）",
            Live2DMotionCatalog.describe("LuluWolf", 19),
        )
        assertEquals(
            "LuluWolf#20=难过垂头（难过、低落、叹气时；动作是头慢慢垂下去停住，半天才抬回来，幅度深；配流泪；神态：眉眼低垂、嘴角下压）",
            Live2DMotionCatalog.describe("LuluWolf", 20),
        )
        assertNull(Live2DMotionCatalog.describe("LuluWolf", 99))
        assertNull(Live2DMotionCatalog.describe("Lulu", 0))
    }

    @Test fun dailyRepertoireIsSmallAndKeepsAllOriginalMotionIdsAvailable() {
        val available = (0..48).map { "LuluWolf" to it }
        val sections = Live2DMotionCatalog.selectionSections(available)
        val primary = sections.filterNot { it.supplementary }
        assertEquals(4, primary.size)
        assertEquals(29, primary.sumOf { it.motions.size })
        assertEquals(29, Live2DMotionCatalog.everydayIds.size)
        assertEquals(49, sections.flatMap { it.motions }.distinct().size)
        assertEquals(available.toSet(), sections.flatMap { it.motions }.toSet())
        assertTrue(sections.last().supplementary)
        // "Change of head direction" alone is not an everyday emotional cue.
        assertTrue(("LuluWolf" to 18) in sections.last().motions)
        assertTrue(("LuluWolf" to 10) in sections.last().motions)
        assertTrue(("LuluWolf" to 34) in primary.flatMap { it.motions })
        assertTrue(("LuluWolf" to 48) in primary.flatMap { it.motions })
    }

    @Test fun motionGuideDescribesEmotionAndStatesBeforeOptionalVariants() {
        val guide = Live2DMotionCatalog.selectionGuide((0..48).map { "LuluWolf" to it })
        assertTrue(guide.contains("情绪·开心与亲近："))
        assertTrue(guide.contains("交流·倾听与回应："))
        assertTrue(guide.contains("状态·休息与放松："))
        assertTrue(guide.contains("LuluWolf#34=认真侧耳倾听"))
        assertTrue(guide.contains("LuluWolf#18=缓慢重心移动"))
        assertTrue(guide.indexOf("情绪·开心与亲近") < guide.indexOf("补充·方向镜像"))
        assertTrue(guide.contains("\\n"))
    }

    @Test fun motionGroupingPreservesThirdPartyAndPartialModelManifest() {
        val available = listOf("Other" to 7, "LuluWolf" to 34,
            "LuluWolf" to 48, "LuluWolf" to 10)
        val sections = Live2DMotionCatalog.selectionSections(available)
        assertEquals(available.toSet(), sections.flatMap { it.motions }.toSet())
        assertTrue(sections.last().supplementary)
        assertTrue(("Other" to 7) in sections.last().motions)
        assertTrue(("LuluWolf" to 10) in sections.last().motions)
        val unrelated = Live2DMotionCatalog.selectionGuide(listOf("Custom" to 2))
        assertTrue(unrelated.contains("Custom#2"))
        assertTrue(!unrelated.contains("LuluWolf"))
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
