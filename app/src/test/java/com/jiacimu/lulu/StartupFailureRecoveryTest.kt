package com.jiacimu.lulu

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class StartupFailureRecoveryTest {
    @Test fun startupFailurePreservesTraceWithoutTouchingUserData() {
        val context = RuntimeEnvironment.getApplication() as Context
        val userPrefs = context.getSharedPreferences("existing_character_test", Context.MODE_PRIVATE)
        userPrefs.edit().putString("character", "still-present").commit()

        StartupFailureRecovery.record(context, "统一时间线初始化",
            IllegalStateException("sample startup exception"))
        val report = StartupFailureRecovery.latest(context)
        assertNotNull(report)
        assertTrue(report.orEmpty().contains("统一时间线初始化"))
        assertTrue(report.orEmpty().contains("java.lang.IllegalStateException"))
        assertTrue(report.orEmpty().contains("sample startup exception"))
        assertTrue(userPrefs.getString("character", "") == "still-present")
    }
}
