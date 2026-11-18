package com.jiacimu.lulu.system

import android.content.Context
import java.time.Instant
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LuluScreenAwarenessTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        LuluScreenAwareness.preferences(context).edit().clear().commit()
    }

    @Test fun requiresSeparateOwnerConsentAndCanRevoke() {
        assertFalse(LuluScreenAwareness.onDemandAllowed(context))
        assertFalse(LuluScreenAwareness.autonomousAllowed(context))
        assertFalse(LuluScreenAwareness.summonAllowed(context))
        assertTrue(LuluScreenAwareness.appTracking(context))
        val prefs = LuluScreenAwareness.preferences(context)
        prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, true).commit()
        assertTrue(LuluScreenAwareness.onDemandAllowed(context))
        assertTrue(LuluScreenAwareness.autonomousAllowed(context))
        assertTrue(LuluScreenAwareness.summonAllowed(context))
        prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, false).commit()
        assertFalse(LuluScreenAwareness.onDemandAllowed(context))
    }

    @Test fun excludesSensitiveAndOwnerSelectedApps() {
        LuluScreenAwareness.preferences(context).edit()
            .putString(LuluScreenAwareness.EXCLUDED, "com.example.secret, com.other.hidden\ncom.third.app").commit()
        assertTrue(LuluScreenAwareness.isExcluded(context, "com.example.secret"))
        assertTrue(LuluScreenAwareness.isExcluded(context, "com.third.app"))
        assertTrue(LuluScreenAwareness.isExcluded(context, "com.android.settings"))
        assertTrue(LuluScreenAwareness.isExcluded(context, "com.google.android.apps.authenticator2"))
        assertTrue(LuluScreenAwareness.isExcluded(context, "com.example.bankapp"))
        assertFalse(LuluScreenAwareness.isExcluded(context, "com.example.game"))
    }

    @Test fun anUnidentifiedOwnerWindowIsNotAPrivacyExclusion() {
        val unknown = LuluScreenAwareness.screenWindowBlockReason(context, "")
        assertTrue(unknown!!.contains("无障碍窗口未就绪"))
        assertTrue(unknown.contains("并非隐私规则禁止"))
        assertNull(LuluScreenAwareness.screenWindowBlockReason(context, "com.jiacimu.lulu"))
        assertNull(LuluScreenAwareness.screenWindowBlockReason(context, "com.tencent.mm"))
        assertTrue(LuluScreenAwareness.screenWindowBlockReason(context,
            "com.android.permissioncontroller")!!.contains("隐私规则排除"))
        LuluScreenAwareness.preferences(context).edit()
            .putString(LuluScreenAwareness.EXCLUDED, "com.example.privateapp").commit()
        assertTrue(LuluScreenAwareness.screenWindowBlockReason(context,
            "com.example.privateapp")!!.contains("隐私规则排除"))
    }

    @Test fun existingCharacterPhoneGrantAllowsOneShotVisionWithoutSecondConsent() {
        val role = "screen-authorized-character-test"
        val prefs = LuluScreenAwareness.preferences(context)
        LuluRolePhoneAuthorization.setGranted(context, role, false)
        try {
            assertFalse(LuluScreenAwareness.onDemandAllowed(context, role))
            assertTrue(LuluRolePhoneAuthorization.setGranted(context, role, true))
            assertTrue(LuluScreenAwareness.onDemandAllowed(context, role))
            assertTrue(LuluScreenAwareness.autonomousAllowed(context, role))
            prefs.edit().putBoolean(LuluScreenAwareness.ON_DEMAND, false).commit()
            assertFalse(LuluScreenAwareness.onDemandAllowed(context, role))
            prefs.edit().putBoolean(LuluScreenAwareness.ON_DEMAND, true)
                .putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
            assertTrue(LuluScreenAwareness.onDemandAllowed(context, role))
            assertFalse(LuluScreenAwareness.autonomousAllowed(context, role))
        } finally {
            LuluRolePhoneAuthorization.setGranted(context, role, false)
        }
        assertFalse(LuluScreenAwareness.onDemandAllowed(context, role))
    }

    @Test fun ownerCanGrantAndRevokeOngoingAutonomousScreenInspection() {
        val prefs = LuluScreenAwareness.preferences(context)
        prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, true)
            .putBoolean(LuluScreenAwareness.AUTONOMOUS, true).commit()
        assertTrue(LuluScreenAwareness.autonomousAllowed(context))
        // Previous five-minute timestamps are diagnostic history, not a veto.
        prefs.edit().putLong("last_autonomous_attempt", System.currentTimeMillis()).commit()
        assertTrue(LuluScreenAwareness.autonomousAllowed(context))
        prefs.edit().putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
        assertFalse(LuluScreenAwareness.autonomousAllowed(context))
        assertTrue(LuluScreenAwareness.onDemandAllowed(context))
    }

    @Test fun noConsentCannotPostInvisibleScreenCapture() = runBlocking {
        val result = JSONObject(LuluScreenAwareness.capture(context,
            userRequested = false, characterId = "test-role", requestId = "test-request"))
        assertFalse(result.optBoolean("success"))
        assertEquals("waiting_user", result.optString("status"))
        assertTrue(result.optString("error").contains("尚未授予"))
        assertTrue(LuluScreenAwareness.diagnostics(context).contains("尚未授予"))
    }

    @Test fun onDemandCanStayEnabledWhenAutonomyDisabled() {
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, true)
            .putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
        assertTrue(LuluScreenAwareness.onDemandAllowed(context))
        assertFalse(LuluScreenAwareness.autonomousAllowed(context))
    }

    private fun completedFrame(userRequested: Boolean = false, sessionId: String = ""): JSONObject =
        JSONObject(LuluScreenAwareness.describedCapture(context, userRequested, "test-role",
            sessionId, "com.example.reader", Instant.parse("2026-10-26T10:00:00Z"),
            "屏幕上有一篇文章", Instant.parse("2026-10-26T10:00:20Z")))

    @Test fun captureTimeIsNotReplacedByAnalysisCompletionTime() {
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, true).commit()
        val result = completedFrame()
        assertTrue(result.optBoolean("success"))
        assertEquals("2026-10-26T10:00:00Z", result.getString("capturedAt"))
        assertEquals("2026-10-26T10:00:20Z", result.getString("analyzedAt"))
        assertEquals("屏幕上有一篇文章", result.getString("visibleDescription"))
    }

    @Test fun revokedAutonomyDiscardsInFlightAutomaticButAllowsExplicitResult() {
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, true)
            .putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
        val automatic = completedFrame()
        assertFalse(automatic.optBoolean("success"))
        assertFalse(automatic.has("visibleDescription"))
        assertTrue(completedFrame(userRequested = true).optBoolean("success"))
    }

    @Test fun revokedOwnerConsentDiscardsBothKindsOfInFlightResult() {
        assertFalse(completedFrame().optBoolean("success"))
        assertFalse(completedFrame(userRequested = true).optBoolean("success"))
    }

    @Test fun endedStudySessionCannotPublishLateObservation() {
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, true).commit()
        val result = completedFrame(sessionId = "ended-session")
        assertFalse(result.optBoolean("success"))
        assertFalse(result.has("visibleDescription"))
    }
}
