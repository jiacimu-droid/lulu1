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
