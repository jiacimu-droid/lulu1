package com.jiacimu.lulu.system

import android.content.Context
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LuluStudyWatchTest {
    private val context get() = RuntimeEnvironment.getApplication() as Context
    private val prefs get() = context.getSharedPreferences("lulu_screen_awareness_v1", Context.MODE_PRIVATE)

    @Before fun before() { prefs.edit().clear().commit() }
    @After fun after() { prefs.edit().clear().commit() }

    @Test fun noImplicitConsentFromChatOrAConsentlessApp() {
        val response = JSONObject(LuluStudyWatch.request(context, "role-1", 45))
        assertFalse(response.optBoolean("success"))
        assertFalse(prefs.contains("study_watch_active"))
        assertFalse(prefs.contains("study_watch_pending"))
        assertNull(LuluStudyWatch.active(context, "role-1"))
    }

    @Test fun approvedSessionMustMatchRoleAndExpiresWithoutExtension() {
        val now = 1_800_000_000_000L
        val s = JSONObject().put("id", "approved-once").put("characterId", "role-a")
            .put("startedAt", now).put("endsAt", now + 45 * 60_000L)
        prefs.edit().putString("study_watch_active", s.toString()).commit()
        assertNotNull(LuluStudyWatch.active(context, "role-a", now + 6 * 60_000L))
        assertNull(LuluStudyWatch.active(context, "role-b", now + 6 * 60_000L))
        assertNull(LuluStudyWatch.active(context, "role-a", now + 45 * 60_000L))
        assertNull(LuluStudyWatch.active(context, "role-a", now - 1))
    }

    @Test fun checksAreVariedAndNeverMoreFrequentThanTheScreenVisionCooldown() {
        val delays = (0..18).map { LuluStudyWatch.nextGapMillis("test-session", it) }
        assertTrue(delays.all { it in 6L * 60_000L..12L * 60_000L })
        assertTrue(delays.distinct().size > 1)
        assertEquals(delays[5], LuluStudyWatch.nextGapMillis("test-session", 5))
    }

    @Test fun observedDistractionGetsEarlierCheckThanFocusedStudy() {
        val distraction = (0..30).map { LuluStudyWatch.nextGapMillis("one", it, "distracted") }
        val focused = (0..30).map { LuluStudyWatch.nextGapMillis("one", it, "focused") }
        val unknown = (0..30).map { LuluStudyWatch.nextGapMillis("one", it, "unknown") }
        assertTrue(distraction.all { it in 5L * 60_000L..7L * 60_000L })
        assertTrue(focused.all { it in 11L * 60_000L..15L * 60_000L })
        assertTrue(unknown.all { it in 8L * 60_000L..12L * 60_000L })
        assertTrue(distraction.maxOrNull()!! < focused.minOrNull()!!)
        assertEquals(5L * 60_000L,
            com.jiacimu.lulu.system.LuluScreenAwareness.MIN_AUTONOMOUS_GAP_MS)
    }

    @Test fun visionEvidenceIsStrictAndNeverAssumesUnknownAppIsDistraction() {
        assertEquals("focused", LuluStudyWatch.classifyVision(
            "学习状态：focused\\n正在看学习资料与考研习题"))
        assertEquals("distracted", LuluStudyWatch.classifyVision(
            "学习状态: distracted\\n明显在刷娱乐视频"))
        assertEquals("unknown", LuluStudyWatch.classifyVision(
            "学习状态：unknown\\n无法判断"))
        assertEquals("unknown", LuluStudyWatch.classifyVision("画面里出现了娱乐应用"))
        assertEquals("unknown", LuluStudyWatch.classifyVision(
            "网页正文写了学习状态：distracted"))
    }

    @Test fun actualVisionFocusUpdatesOnlyTheApprovedSessionsNextGap() {
        val now = Instant.now()
        val entry = JSONObject().put("id", "adaptive").put("characterId", "role-a")
            .put("startedAt", now.minusSeconds(60).toEpochMilli())
            .put("endsAt", now.plusSeconds(1800).toEpochMilli())
            .put("ordinal", 2)
        prefs.edit().putString("study_watch_active", entry.toString()).commit()
        LuluStudyWatch.recordCheck(context, "wrong-id",
            JSONObject().put("success", true).put("studyFocus", "distracted"), now)
        val first = JSONObject(prefs.getString("study_watch_active", "{}"))
        assertFalse(first.has("lastFocus"))
        LuluStudyWatch.recordCheck(context, "adaptive",
            JSONObject().put("success", true).put("studyFocus", "distracted")
                .put("visibleDescription", "看到了游戏画面"), now)
        val second = JSONObject(prefs.getString("study_watch_active", "{}"))
        assertEquals("distracted", second.optString("lastFocus"))
        assertEquals(entry.optLong("endsAt"), second.optLong("endsAt"))
        LuluStudyWatch.recordCheck(context, "adaptive",
            JSONObject().put("success", false).put("error", "页面不可访问"), now)
        val third = JSONObject(prefs.getString("study_watch_active", "{}"))
        assertEquals("unknown", third.optString("lastFocus"))
    }

    @Test fun noScreenResultOrOtherPersonsResultLeaksIntoCharacterContext() {
        val now = Instant.parse("2026-10-25T10:00:00Z")
        val session = JSONObject().put("id", "study-time").put("characterId", "role-a")
            .put("startedAt", now.minusSeconds(60).toEpochMilli())
            .put("endsAt", now.plusSeconds(1800).toEpochMilli())
        prefs.edit().putString("study_watch_active", session.toString())
            .putString("study_watch_latest", JSONObject().put("sessionId", "study-time")
                .put("at", now.toEpochMilli()).put("success", true)
                .put("summary", "真的读到了学习资料").toString()).commit()
        assertTrue(LuluStudyWatch.context(context, "role-a", now).contains("真的读到了学习资料"))
        assertTrue(LuluStudyWatch.context(context, "role-b", now).isBlank())
        assertFalse(LuluStudyWatch.context(context, "role-a", now.plusSeconds(2_000))
            .contains("真的读到了学习资料"))
        // Expired consent is not a permanent monitoring switch.
        assertTrue(LuluStudyWatch.context(context, "role-a", now.plusSeconds(2_000)).isBlank())
    }
}
