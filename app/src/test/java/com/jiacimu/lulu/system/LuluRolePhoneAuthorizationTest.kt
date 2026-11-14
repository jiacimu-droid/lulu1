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

/** One source of truth for all role phone tools, despite legacy per-action flags. */
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
                .putBoolean("$first:media", true)
                .putBoolean("$first:device_read", true)
                .putBoolean("$first:alarm", true)
                .putBoolean("$first:wake_music_enabled", true).commit()
            legacyChat.edit().putBoolean(first, true).commit()
            legacyShop.edit().putBoolean(first, true).commit()

            fun autonomous(tool: String): Boolean = CapabilityRegistry.allows(
                context, first, CapabilityRegistry.find(tool)!!, userRequested = false)
            assertFalse(LuluRolePhoneAuthorization.isGranted(context, first))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(context, first))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(context, first))
            assertFalse(WakeActionPlanner.musicAllowed(context, first))
            for (tool in listOf("background_phone", "get_battery", "control_music",
                "create_alarm", "cloud_task")) assertFalse(tool, autonomous(tool))

            assertTrue(LuluRolePhoneAuthorization.setGranted(context, first, true))
            assertTrue(LuluRolePhoneAuthorization.isGranted(context, first))
            assertTrue(LuluVirtualChatSendPolicy.isEnabled(context, first))
            assertTrue(LuluShoppingAssistPolicy.isEnabled(context, first))
            assertTrue(WakeActionPlanner.musicAllowed(context, first))
            for (tool in listOf("background_phone", "get_battery", "control_music",
                "create_alarm", "cloud_task")) assertTrue(tool, autonomous(tool))
            assertFalse(LuluRolePhoneAuthorization.isGranted(context, second))
            assertFalse(LuluRolePhoneAuthorization.setGranted(context, "resident:test", true))

            assertTrue(LuluRolePhoneAuthorization.setGranted(context, first, false))
            assertFalse(LuluVirtualChatSendPolicy.isEnabled(context, first))
            assertFalse(LuluShoppingAssistPolicy.isEnabled(context, first))
            assertFalse(WakeActionPlanner.musicAllowed(context, first))
            for (tool in listOf("background_phone", "get_battery", "control_music",
                "create_alarm", "cloud_task")) assertFalse(tool, autonomous(tool))
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
