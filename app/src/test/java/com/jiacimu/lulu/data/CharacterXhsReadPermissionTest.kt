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

    @Test fun legacyXhsCapabilityIsNotRegisteredEvenWithSavedOldConsent() {
        val key = "$role:xhs_read"
        val existed = prefs.contains(key)
        val before = prefs.getBoolean(key, false)
        try {
            prefs.edit().putBoolean(key, true).commit()
            assertNull(CapabilityRegistry.find("read_xiaohongshu"))
            assertFalse(CapabilityRegistry.canChoose("read_xiaohongshu"))
            assertTrue(CapabilityRegistry.canChoose("background_phone"))
            val prompt = CapabilityRegistry.phoneOperationContext(context, role)
            assertFalse(prompt.contains("read_xiaohongshu"))
            assertTrue(prompt.contains("background_phone"))
        } finally {
            val editor = prefs.edit()
            if (existed) editor.putBoolean(key, before) else editor.remove(key)
            editor.commit()
        }
    }
}
