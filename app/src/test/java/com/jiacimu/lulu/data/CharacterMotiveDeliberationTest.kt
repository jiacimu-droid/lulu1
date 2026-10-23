package com.jiacimu.lulu.data

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class CharacterMotiveDeliberationTest {
    private val now = Instant.parse("2026-10-23T14:00:00Z")

    @Test fun multipleWishesRespectWaitingFeedbackAndPausedStates() {
        val items = JSONArray()
            .put(JSONObject().put("id", "a").put("aim", "学做披萨")
                .put("status", "active").put("reviewAfter", "2026-10-24T14:00:00Z"))
            .put(JSONObject().put("id", "b").put("aim", "学习相处")
                .put("status", "waiting_feedback"))
            .put(JSONObject().put("id", "c").put("aim", "暂时休息")
                .put("status", "paused"))
        val text = CharacterMotiveDeliberation.context(items, now)
        assertTrue(text.contains("motiveId=a"))
        assertTrue(text.contains("motiveId=b"))
        assertTrue(text.contains("约定未来再次考虑"))
        assertTrue(text.contains("行动后还在等真实反馈"))
        assertTrue(text.contains("暂缓而非完成"))
        assertFalse(text.contains("motiveId=c；想做"))
    }

    @Test fun emptyMotiveStateNeverCreatesActivity() {
        assertTrue(CharacterMotiveDeliberation.context(JSONArray(), now).isBlank())
    }
}
