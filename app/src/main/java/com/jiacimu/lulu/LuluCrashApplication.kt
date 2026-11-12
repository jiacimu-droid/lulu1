package com.jiacimu.lulu

import android.app.Application
import android.content.ComponentCallbacks2
import com.jiacimu.lulu.system.LuluScreenFrameHandoff

/**
 * Install crash diagnostics before Android instantiates MigrationActivity or
 * Compose. A launch failure can happen before Activity.onCreate is entered.
 * This class deliberately does NOT touch any chat / role / model stores.
 */
class LuluCrashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        StartupFailureRecovery.install(this)
        StartupFailureRecovery.stage("Android Application 启动完成；等待桌面 Activity")
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Screenshot handoffs are optional, but can hold large base64 strings.
        // Do not clear solely for UI_HIDDEN: the owner's role may be executing
        // its authorized background phone task while the main UI is closed.
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW &&
            level != ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            LuluScreenFrameHandoff.clear()
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        LuluScreenFrameHandoff.clear()
    }
}
