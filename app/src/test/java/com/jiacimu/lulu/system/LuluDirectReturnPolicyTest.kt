package com.jiacimu.lulu.system

import android.content.Context
import com.jiacimu.lulu.data.CapabilityRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LuluDirectReturnPolicyTest {
    private fun reason(
        granted: Boolean = true, tracking: Boolean = true,
        connected: Boolean = true, interactive: Boolean = true,
        locked: Boolean = false, foreground: String = "com.ss.android.ugc.aweme",
        excluded: Boolean = false,
    ) = LuluDirectReturnPolicy.blockReason(
        granted, tracking, connected, interactive, locked,
        foreground, "com.jiacimu.lulu", excluded,
    )

    @Test fun ownerConsentIsSeparateFromScreenshotAndNormalScreenControl() {
        val app = RuntimeEnvironment.getApplication() as Context
        val prefs = LuluScreenAwareness.preferences(app)
        prefs.edit().clear().commit()
        val cap = requireNotNull(CapabilityRegistry.find("bring_lulu_front"))
        assertFalse(cap.retrySafe)
        assertFalse(LuluScreenAwareness.directReturnAllowed(app))
        assertFalse(CapabilityRegistry.allows(app, "role-test", cap, false))
        assertFalse("even a direct owner request cannot silently skip this interruptive opt-in",
            CapabilityRegistry.allows(app, "role-test", cap, true))
        prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, true).commit()
        assertFalse("general screenshot consent does not grant interruptive app switching",
            LuluScreenAwareness.directReturnAllowed(app))
        prefs.edit().putBoolean(LuluScreenAwareness.DIRECT_RETURN, true).commit()
        assertTrue(LuluScreenAwareness.directReturnAllowed(app))
        assertTrue(CapabilityRegistry.allows(app, "role-test", cap, false))
        assertFalse("digital-world residents cannot control the owner's device",
            CapabilityRegistry.allows(app, "resident:barista", cap, true))
        prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, false).commit()
        assertFalse(LuluScreenAwareness.directReturnAllowed(app))
        prefs.edit().clear().commit()
    }

    @Test fun onlyVerifiedUnlockedOtherAppsCanBeInterrupted() {
        assertNull(reason())
        assertNotNull(reason(granted = false))
        assertNotNull(reason(tracking = false))
        assertNotNull(reason(connected = false))
        assertNotNull(reason(interactive = false))
        assertNotNull(reason(locked = true))
        assertNotNull(reason(foreground = ""))
        assertNotNull(reason(foreground = "com.jiacimu.lulu"))
        assertNotNull(reason(excluded = true))
        assertNotNull(reason(foreground = "com.android.systemui", excluded = true))
    }

    @Test fun protectedPackagesAreNotEligibleForDirectReturn() {
        val app = RuntimeEnvironment.getApplication() as Context
        listOf(
            "com.android.systemui", "com.android.permissioncontroller",
            "com.android.settings", "com.miui.securitycenter",
            "com.android.incallui", "com.google.android.dialer",
            "com.example.bank.app", "com.google.android.apps.authenticator",
        ).forEach { name ->
            assertTrue(name, LuluScreenAwareness.isExcluded(app, name))
        }
        assertFalse(LuluScreenAwareness.isExcluded(app, "com.ss.android.ugc.aweme"))
    }
}
