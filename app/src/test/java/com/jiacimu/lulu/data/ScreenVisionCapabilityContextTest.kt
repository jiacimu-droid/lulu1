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
        // Screen vision also honors the per-role phone grant. Make the
        // "unconsented" test genuinely unconsented rather than inheriting an
        // unrelated Robolectric test's persistent role permission.
        val rolePrefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val grantKey = "$role:screen"
        val oldGrant = rolePrefs.getBoolean(grantKey, false)
        val hadGrant = rolePrefs.contains(grantKey)
        try {
            p.edit().clear().commit()
            rolePrefs.edit().putBoolean(grantKey, false).commit()
            assertNotNull(CapabilityRegistry.find("capture_screen"))
            assertNotNull(CapabilityRegistry.find("summon_lulu"))
            val shown = CapabilityRegistry.screenVisionContext(context, role)
            assertTrue(shown.contains("capture_screen 工具已注册=true"))
            assertTrue(shown.contains("主人按需识图授权=false"))
            assertTrue(shown.contains("先由主人"))
            assertFalse(CapabilityRegistry.allows(context, role,
                CapabilityRegistry.find("capture_screen")!!, userRequested = true))
            assertFalse(CapabilityRegistry.allows(context, role,
                CapabilityRegistry.find("read_screen")!!, userRequested = true))
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
            val roleEditor = rolePrefs.edit()
            if (hadGrant) roleEditor.putBoolean(grantKey, oldGrant)
            else roleEditor.remove(grantKey)
            roleEditor.commit()
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
            assertTrue(screen.contains("无论主人有没有发消息或要求查看、是否正在学习监督"))
            assertFalse(screen.contains("主人确认之前不得查看屏幕"))
            assertTrue(screen.contains("旧工具名read_screen是相同截图能力的兼容别名"))
            assertTrue(screen.contains("不再提供纯文字假观察"))
            assertTrue(screen.contains("read_screen也会取得新截图"))
            assertTrue(screen.contains("不要要求MediaProjection"))
            assertFalse(screen.contains("两次至少间隔5分钟"))
            assertFalse(screen.contains("危险"))
            assertTrue(CapabilityRegistry.allows(context, role,
                CapabilityRegistry.find("capture_screen")!!, userRequested = false))
            assertTrue(CapabilityRegistry.allows(context, role,
                CapabilityRegistry.find("read_screen")!!, userRequested = false))
            assertEquals("screen_vision", CapabilityRegistry.find("read_screen")!!.permission)
            assertFalse(CapabilityRegistry.find("read_screen")!!.retrySafe)
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

    @Test fun savedRoleGrantSurvivesOldScreenshotAndExpiredPendingRequest() {
        val p = LuluScreenAwareness.preferences(context)
        val before = p.all.toMap()
        val rolePrefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val grantKey = "$role:screen"
        val oldGrant = rolePrefs.getBoolean(grantKey, false)
        val hadGrant = rolePrefs.contains(grantKey)
        try {
            p.edit().clear()
                .putBoolean(LuluScreenAwareness.CONSENT, false)
                .putBoolean(LuluScreenAwareness.ON_DEMAND, true)
                .putBoolean(LuluScreenAwareness.AUTONOMOUS, true)
                // A previous screenshot and an expired unanswered popup are
                // neither a revocation nor a new Android permission request.
                .putLong("last_autonomous_attempt", 1L)
                .putString("pending_capture_permission", "{\"id\":\"old\",\"at\":1}")
                .commit()
            rolePrefs.edit().putBoolean(grantKey, true).commit()

            assertTrue(LuluScreenAwareness.onDemandAllowed(context, role))
            assertTrue(LuluScreenAwareness.autonomousAllowed(context, role))
            assertNull(LuluScreenAwareness.authorizationBlockReason(
                context, role, userRequested = false))
            val shown = CapabilityRegistry.screenVisionContext(context, role)
            assertTrue(shown.contains("本角色手机屏幕授权=true"))
            assertTrue(shown.contains("没有截图次数或时间倒计时"))
            assertTrue(shown.contains("不会让Android自动收回这些授权"))
            assertTrue(shown.contains("授权=true且设备条件可用时直接调用工具"))

            p.edit().putBoolean(LuluScreenAwareness.AUTONOMOUS, false).commit()
            assertTrue(LuluScreenAwareness.onDemandAllowed(context, role))
            assertFalse(LuluScreenAwareness.autonomousAllowed(context, role))
            assertTrue(LuluScreenAwareness.authorizationBlockReason(
                context, role, userRequested = false)!!.contains("自主屏幕观察"))
            assertNull(LuluScreenAwareness.authorizationBlockReason(
                context, role, userRequested = true))

            p.edit().putBoolean(LuluScreenAwareness.ON_DEMAND, false).commit()
            assertTrue(LuluScreenAwareness.authorizationBlockReason(
                context, role, userRequested = true)!!.contains("按需屏幕视觉开关"))
        } finally {
            val editor = p.edit().clear()
            before.forEach { (key, value) -> when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
            } }
            editor.commit()
            val roleEditor = rolePrefs.edit()
            if (hadGrant) roleEditor.putBoolean(grantKey, oldGrant)
            else roleEditor.remove(grantKey)
            roleEditor.commit()
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
