package com.jiacimu.lulu.data

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class CharacterXhsReadPermissionTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val role = "xhs-permission-role"
    private val prefs = context.getSharedPreferences("lulu_execution_policy", Context.MODE_PRIVATE)

    @Test fun roleCanAutonomouslyReadFromAlreadyAuthorizedXhsWithoutSeparateHiddenGrant() {
        val cap = CapabilityRegistry.find("read_xiaohongshu")!!
        val key = "$role:xhs_read"
        val before = prefs.getBoolean(key, false)
        val existed = prefs.contains(key)
        try {
            prefs.edit().remove(key).commit()
            assertTrue(CapabilityRegistry.allows(context, role, cap, false))
            prefs.edit().putBoolean(key, false).commit()
            assertFalse(CapabilityRegistry.allows(context, role, cap, false))
            // Explicit user request remains permitted even if autonomous browsing
            // is switched off. Browser availability is checked by execution.
            assertTrue(CapabilityRegistry.allows(context, role, cap, true))
        } finally {
            val editor = prefs.edit()
            if (existed) editor.putBoolean(key, before) else editor.remove(key)
            editor.commit()
        }
    }
}
