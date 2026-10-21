package com.jiacimu.lulu

import android.app.Application

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
}
