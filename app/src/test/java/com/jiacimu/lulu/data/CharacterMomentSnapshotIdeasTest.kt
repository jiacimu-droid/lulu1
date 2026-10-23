package com.jiacimu.lulu.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterMomentSnapshotIdeasTest {
    @Test fun moreThanThreeDistinctActuallyAuthoredIdeasStayVisible() {
        val entries = JSONArray().put("想看看那本书").put("想约好友玩游戏")
            .put("想试一种新口味").put("想整理房间").put("想问问朋友近况")
        val visible = CharacterMomentSnapshot.reflection(null,
            appraisal = JSONObject().put("privateIdeas", entries))
        assertEquals(5, visible.lines().size)
        assertTrue(visible.contains("想问问朋友近况"))
    }

    @Test fun noEvidenceDoesNotSynthesizePrivateIdeas() {
        assertEquals("", CharacterMomentSnapshot.reflection(null, appraisal = JSONObject()))
    }
}
