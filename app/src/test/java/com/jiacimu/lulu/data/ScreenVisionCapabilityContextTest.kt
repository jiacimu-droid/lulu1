package com.jiacimu.lulu.data

import android.content.Context
import com.jiacimu.lulu.system.LuluScreenAwareness
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A tool's registration, model-visible availability, and permission are not interchangeable. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ScreenVisionCapabilityContextTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val role = "screen-vision-routing-test"

    @Test fun unconsentedToolIsRegisteredButNotClaimedAsExecuted() {
        val p = LuluScreenAwareness.preferences(context)
        val previous = p.all.toMap()
        try {
            p.edit().clear().commit()
            assertNotNull(CapabilityRegistry.find("capture_screen"))
            assertNotNull(CapabilityRegistry.find("summon_lulu"))
            val shown = CapabilityRegistry.screenVisionContext(context, role)
            assertTrue(shown.contains("capture_screen 工具已注册=true"))
            assertTrue(shown.contains("主人按需识图授权=false"))
            assertTrue(shown.contains("先由主人"))
            assertFalse(CapabilityRegistry.allows(context, role,
                CapabilityRegistry.find("capture_screen")!!, userRequested = true))
        } finally {
            val editor = p.edit().clear()
            previous.forEach { (key, value) ->
                when (value) {
                    is Boolean -> editor.putBoolean(key, value)
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                }
            }
            editor.commit()
        }
    }

    @Test fun authorizedRoleSeesDirectCaptureActionWithoutRepeatedPermissionLanguage() {
        val prefs = LuluScreenAwareness.preferences(context)
        val before = prefs.all.toMap()
        try {
            prefs.edit().putBoolean(LuluScreenAwareness.CONSENT, true)
                .putBoolean(LuluScreenAwareness.AUTONOMOUS, true).commit()
            val screen = CapabilityRegistry.screenVisionContext(context, role)
            assertTrue(screen.contains("角色自主观察授权=true"))
            assertTrue(screen.contains("action=tool,tool=capture_screen,args={}"))
            assertTrue(screen.contains("无须逐次申请"))
            assertFalse(screen.contains("两次至少间隔5分钟"))
            assertFalse(screen.contains("危险"))
            assertTrue(CapabilityRegistry.allows(context, role,
                CapabilityRegistry.find("capture_screen")!!, userRequested = false))
        } finally {
            val edit = prefs.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is Boolean -> edit.putBoolean(key, value)
                is String -> edit.putString(key, value)
                is Long -> edit.putLong(key, value)
                is Int -> edit.putInt(key, value)
            } }
            edit.commit()
        }
    }

    @Test fun userRequestedViewingWorksWhenAutonomousViewingIsOff() {
        val p = LuluScreenAwareness.preferences(context)
        val oldConsent = p.getBoolean(LuluScreenAwareness.CONSENT, false)
        val hadConsent = p.contains(LuluScreenAwareness.CONSENT)
        val oldAuto = p.getBoolean(LuluScreenAwareness.AUTONOMOUS, true)
        val hadAuto = p.contains(LuluScreenAwareness.AUTONOMOUS)
        try {
            p.edit().putBoolean(LuluScreenAwareness.CONSENT, true)
                .putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
            val cap = CapabilityRegistry.find("capture_screen")!!
            assertTrue(CapabilityRegistry.allows(context, role, cap, userRequested = true))
            assertFalse(CapabilityRegistry.allows(context, role, cap, userRequested = false))
            val shown = CapabilityRegistry.screenVisionContext(context, role)
            assertTrue(shown.contains("capture_screen 工具已注册=true"))
            assertTrue(shown.contains("主人按需识图授权=true"))
            assertTrue(shown.contains("角色自主观察授权=false"))
            assertTrue(shown.contains("action=tool,tool=capture_screen,args={}"))
            assertTrue(shown.contains("summon_lulu 工具已注册=true"))
        } finally {
            val editor = p.edit()
            if (hadConsent) editor.putBoolean(LuluScreenAwareness.CONSENT, oldConsent)
            else editor.remove(LuluScreenAwareness.CONSENT)
            if (hadAuto) editor.putBoolean(LuluScreenAwareness.AUTONOMOUS, oldAuto)
            else editor.remove(LuluScreenAwareness.AUTONOMOUS)
            editor.commit()
        }
    }
}
