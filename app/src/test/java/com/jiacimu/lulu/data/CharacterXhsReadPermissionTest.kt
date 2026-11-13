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

    @Test fun legacyXhsReaderIsRetiredEvenWithOldGrantOrExplicitRequest() {
        val cap = CapabilityRegistry.find("read_xiaohongshu")!!
        val key = "$role:xhs_read"
        val before = prefs.getBoolean(key, false)
        val existed = prefs.contains(key)
        try {
            assertTrue(cap.retired)
            assertFalse(CapabilityRegistry.canChoose("read_xiaohongshu"))
            prefs.edit().putBoolean(key, true).commit()
            assertFalse(CapabilityRegistry.allows(context, role, cap, false))
            assertFalse(CapabilityRegistry.allows(context, role, cap, true))
            assertTrue(CapabilityRegistry.canChoose("background_phone"))
        } finally {
            val editor = prefs.edit()
            if (existed) editor.putBoolean(key, before) else editor.remove(key)
            editor.commit()
        }
    }
}
