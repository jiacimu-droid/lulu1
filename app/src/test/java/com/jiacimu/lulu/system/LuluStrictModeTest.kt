package com.jiacimu.lulu.system

import android.content.Context
import com.jiacimu.lulu.data.CapabilityRegistry
import com.jiacimu.lulu.data.ToolExecutionStatus
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluStrictModeTest {
    private val app get() = RuntimeEnvironment.getApplication() as Context

    @Test fun modeIsRegisteredButNotAutomaticallyGranted() {
        val context = app
        val settings = LuluScreenAwareness.preferences(context)
        settings.edit().clear().commit()
        context.getSharedPreferences("lulu_strict_mode_v1", Context.MODE_PRIVATE)
            .edit().clear().commit()
        val cap = requireNotNull(CapabilityRegistry.find("strict_supervision"))
        assertFalse(cap.retrySafe)
        assertFalse(LuluStrictMode.ownerGranted(context))
        assertEquals("denied", JSONObject(LuluStrictMode.command(context, "role-a",
            JSONObject().put("action", "start").put("mode", "return")))
            .optString("status"))
        settings.edit().putBoolean(LuluScreenAwareness.CONSENT, true).commit()
        assertFalse("Screen vision consent does not grant ongoing interception",
            LuluStrictMode.ownerGranted(context))
        settings.edit().putBoolean(LuluStrictMode.GRANT, true).commit()
        assertTrue(LuluStrictMode.ownerGranted(context))
        settings.edit().putBoolean(LuluScreenAwareness.CONSENT, false).commit()
        assertFalse(LuluStrictMode.ownerGranted(context))
        settings.edit().clear().commit()
    }

    @Test fun policyRespectsModeLuluProtectedAppsAndRoleExceptions() {
        val lulu = "com.jiacimu.lulu"
        val other = "com.ss.android.ugc.aweme"
        val allowed = setOf("com.tencent.mm")
        assertFalse(LuluStrictMode.shouldReturn("observe", other, lulu, emptySet(), false))
        assertTrue(LuluStrictMode.shouldReturn("return", other, lulu, allowed, false))
        assertFalse(LuluStrictMode.shouldReturn("return", "com.tencent.mm", lulu, allowed, false))
        assertFalse(LuluStrictMode.shouldReturn("return", lulu, lulu, emptySet(), false))
        assertFalse(LuluStrictMode.shouldReturn("return", "com.android.settings", lulu,
            emptySet(), true))
        assertFalse(LuluStrictMode.shouldReturn("return", "", lulu, emptySet(), false))
    }

    @Test fun ownerCanRevokeAuthorizationEvenWithNoActiveSession() {
        val context = app
        val settings = LuluScreenAwareness.preferences(context)
        settings.edit().putBoolean(LuluScreenAwareness.CONSENT, true)
            .putBoolean(LuluStrictMode.GRANT, true).commit()
        assertTrue(LuluStrictMode.ownerGranted(context))
        LuluStrictMode.ownerStop(context)
        assertFalse(LuluStrictMode.ownerGranted(context))
        assertEquals("denied", JSONObject(LuluStrictMode.command(context, "role-a",
            JSONObject().put("action", "start").put("goal", "监督学习")))
            .optString("status"))
        settings.edit().clear().commit()
    }

    @Test fun highIntensityIsLocalAndBoundedBySeparatePaidReviewGap() {
        assertEquals(10_000L, LuluStrictMode.LOCAL_SAMPLE_GAP)
        assertEquals(20_000L, LuluStrictMode.REVIEW_GAP)
        assertTrue(LuluStrictMode.REVIEW_GAP > LuluStrictMode.LOCAL_SAMPLE_GAP)
    }

    @Test fun blockedAppsAndHomeModeRespectExplicitExceptions() {
        val lulu = "com.jiacimu.lulu"
        val video = "com.ss.android.ugc.aweme"
        val study = "com.reader.books"
        val blocked = setOf(video)
        assertTrue(LuluStrictMode.shouldReturn("home", video, lulu,
            emptySet(), false, blocked))
        assertTrue(LuluStrictMode.shouldReturn("return", video, lulu,
            emptySet(), false, blocked))
        assertFalse(LuluStrictMode.shouldReturn("return", study, lulu,
            emptySet(), false, blocked))
        assertFalse(LuluStrictMode.shouldReturn("home", video, lulu,
            setOf(video), false, blocked))
        assertFalse("Explicit targeted-home mode must not become lockdown when empty",
            LuluStrictMode.shouldReturn("home", video, lulu,
                emptySet(), false, emptySet()))
        assertFalse(LuluStrictMode.shouldReturn("home", video, lulu,
            emptySet(), true, blocked))
        assertFalse(LuluStrictMode.shouldReturn("observe", video, lulu,
            emptySet(), false, blocked))
    }

    @Test fun fullLockdownBlocksAllOrdinaryAppsButNotLuluOrProtectedSystem() {
        val lulu = "com.jiacimu.lulu"
        val ordinaryVideo = "com.ss.android.ugc.aweme"
        val ordinaryReader = "com.reader.books"
        // Explicit lockdown overrides ordinary per-app exceptions, but NEVER
        // overrides the Android/system/privacy protection boundary.
        assertTrue(LuluStrictMode.shouldReturn("lockdown", ordinaryVideo, lulu,
            setOf(ordinaryVideo), false, setOf(ordinaryReader)))
        assertTrue(LuluStrictMode.shouldReturn("lockdown", ordinaryReader, lulu,
            emptySet(), false, emptySet()))
        assertFalse(LuluStrictMode.shouldReturn("lockdown", lulu, lulu,
            emptySet(), false))
        assertFalse(LuluStrictMode.shouldReturn("lockdown", "com.android.settings", lulu,
            emptySet(), true))
        assertFalse(LuluStrictMode.shouldReturn("lockdown", "", lulu,
            emptySet(), false))
        assertFalse(LuluStrictMode.shouldReturn("invalid", ordinaryVideo, lulu,
            emptySet(), false))
    }

    @Test fun expiredRevokedAndOtherRolesCannotAdoptStoredSession() {
        val context = app
        val settings = LuluScreenAwareness.preferences(context)
        val sessionPrefs = context.getSharedPreferences("lulu_strict_mode_v1", Context.MODE_PRIVATE)
        settings.edit().clear().putBoolean(LuluScreenAwareness.CONSENT, true)
            .putBoolean(LuluStrictMode.GRANT, true).commit()
        sessionPrefs.edit().clear().commit()
        val now = System.currentTimeMillis()
        val session = JSONObject().put("id", "test-1").put("characterId", "role-a")
            .put("mode", "return").put("startedAt", now - 10_000L)
            .put("endsAt", now + 60_000L)
        sessionPrefs.edit().putString("active", session.toString()).commit()
        assertEquals("test-1", LuluStrictMode.active(context, now)?.optString("id"))
        assertEquals("inactive", JSONObject(LuluStrictMode.status(context, "role-b"))
            .optString("status"))
        assertEquals("active", JSONObject(LuluStrictMode.status(context, "role-a"))
            .optString("status"))
        assertNull(LuluStrictMode.active(context, now + 60_001L))
        settings.edit().putBoolean(LuluStrictMode.GRANT, false).commit()
        assertNull(LuluStrictMode.active(context, now))
        settings.edit().clear().commit()
        sessionPrefs.edit().clear().commit()
    }

    @Test fun activeAndStoppedReceiptsCanSatisfyExplicitStepDependency() {
        assertTrue(ToolExecutionStatus.completed(
            JSONObject().put("success", true).put("status", "active")))
        assertTrue(ToolExecutionStatus.completed(
            JSONObject().put("success", true).put("status", "stopped")))
        assertFalse(ToolExecutionStatus.completed(
            JSONObject().put("success", false).put("status", "active")))
    }
}
