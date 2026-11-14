package com.jiacimu.lulu.system

import android.content.Context
import com.jiacimu.lulu.data.CapabilityRegistry
import com.jiacimu.lulu.data.WakeActionPlanner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Virtual-screen consent covers in-screen operations, not native media/device tools. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class LuluRolePhoneAuthorizationTest {
    @Test fun singleVirtualScreenGrantEnablesAllAndRevokesAllWithoutLegacyBypass() {
        val context = RuntimeEnvironment.getApplication()
        val first = "single-phone-grant-first"
        val second = "single-phone-grant-second"
        val policy = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)
        val old = policy.all.toMap()
        val legacyChat = context.getSharedPreferences("lulu_virtual_chat_send_owner_v1", Context.MODE_PRIVATE)
        val legacyShop = context.getSharedPreferences("lulu_virtual_shopping_owner_v1", Context.MODE_PRIVATE)
        val chatOld = legacyChat.all.toMap()
        val shopOld = legacyShop.all.toMap()
        try {
            policy.edit().remove("$first:screen").remove("$second:screen")
                .putBoolean("$first:media", false)
                .putBoolean("$first:device_read", false)
                .putBoolean("$first:alarm", false)
                .putBoolean("$first:cloud", false)
                .putBoolean("$first:wake_music_enabled", true).commit()
            // Old independent chat/shopping toggles must never bypass the screen grant.
            legacyChat.edit().putBoolean(first, true).commit()
            legacyShop.edit().putBoolean(first, true).commit()

            fun autonomous(tool: String): Boolean = CapabilityRegistry.allows(
                context, first, CapabilityRegistry.find(tool)!!, userRequested = false)
            assertFalse(LuluRolePhoneAuthorization.isGranted(context, first))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(context, first))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(context, first))
            assertFalse(WakeActionPlanner.musicAllowed(context, first))
            assertFalse(autonomous("background_phone"))

            assertTrue(LuluRolePhoneAuthorization.setGranted(context, first, true))
            assertTrue(LuluRolePhoneAuthorization.isGranted(context, first))
            assertTrue(LuluVirtualChatSendPolicy.isEnabled(context, first))
            assertTrue(LuluShoppingAssistPolicy.isEnabled(context, first))
            assertTrue(autonomous("background_phone"))
            // Virtual-screen authorization must NEVER authorize native device tools.
            for (tool in listOf("get_battery", "control_music",
                "create_alarm", "cloud_task")) assertFalse(tool, autonomous(tool))
            assertFalse(WakeActionPlanner.musicAllowed(context, first))
            assertFalse(LuluRolePhoneAuthorization.isGranted(context, second))
            assertFalse(LuluRolePhoneAuthorization.setGranted(context, "resident:test", true))

            // Native media authorization is separate and remains enabled after
            // the virtual-screen grant is revoked.
            policy.edit().putBoolean("$first:media", true).commit()
            assertTrue(autonomous("control_music"))
            assertTrue(WakeActionPlanner.musicAllowed(context, first))
            assertFalse(autonomous("get_battery"))
            assertTrue(LuluRolePhoneAuthorization.setGranted(context, first, false))
            assertFalse(autonomous("background_phone"))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(context, first))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(context, first))
            assertTrue(autonomous("control_music"))
            assertTrue(WakeActionPlanner.musicAllowed(context, first))

            policy.edit().putBoolean("$first:media", false).commit()
            assertFalse(autonomous("control_music"))
            assertFalse(WakeActionPlanner.musicAllowed(context, first))
        } finally {
            fun restore(p: android.content.SharedPreferences, snapshot: Map<String, *>) {
                val e = p.edit().clear()
                snapshot.forEach { (key, value) ->
                    when (value) {
                        is Boolean -> e.putBoolean(key, value)
                        is String -> e.putString(key, value)
                        is Int -> e.putInt(key, value)
                        is Long -> e.putLong(key, value)
                        is Float -> e.putFloat(key, value)
                    }
                }
                e.commit()
            }
            restore(policy, old)
            restore(legacyChat, chatOld)
            restore(legacyShop, shopOld)
        }
    }
}
