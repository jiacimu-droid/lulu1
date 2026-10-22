package com.jiacimu.lulu.system

import android.content.Context
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

    @Test fun gapLimitsAutonomousNotExplicitCaptureAndNeverSchedulesAJob() {
        LuluScreenAwareness.preferences(context).edit()
            .putLong("last_autonomous_attempt", 1_000_000L).commit()
        assertEquals(300_000L, LuluScreenAwareness.cooldownRemaining(context, 1_000_000L))
        assertEquals(1L, LuluScreenAwareness.cooldownRemaining(context, 1_299_999L))
        assertEquals(0L, LuluScreenAwareness.cooldownRemaining(context, 1_300_000L))
    }

    @Test fun onDemandCanStayEnabledWhenAutonomyDisabled() {
        LuluScreenAwareness.preferences(context).edit()
            .putBoolean(LuluScreenAwareness.CONSENT, true)
            .putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
        assertTrue(LuluScreenAwareness.onDemandAllowed(context))
        assertFalse(LuluScreenAwareness.autonomousAllowed(context))
    }
}
