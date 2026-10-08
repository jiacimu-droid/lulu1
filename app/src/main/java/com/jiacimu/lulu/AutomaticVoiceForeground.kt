package com.jiacimu.lulu

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

/** Automatic chat and meeting speech must not be charged when the app is backgrounded. */
internal object AutomaticVoiceForeground {
    private var installed = false
    private val resumed = mutableSetOf<Activity>()
    private val onLeaveCallbacks = mutableSetOf<() -> Unit>()

    @Synchronized fun install(context: Context) {
        if (installed) return
        val app = context.applicationContext as? Application ?: return
        installed = true
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) {
                synchronized(this@AutomaticVoiceForeground) { resumed.add(activity) }
            }
            override fun onActivityPaused(activity: Activity) {
                val callbacks = synchronized(this@AutomaticVoiceForeground) {
                    resumed.remove(activity)
                    if (resumed.isEmpty()) onLeaveCallbacks.toList() else emptyList()
                }
                callbacks.forEach { it() }
            }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) {
                synchronized(this@AutomaticVoiceForeground) { resumed.remove(activity) }
            }
        })
    }

    @Synchronized fun visible(): Boolean = resumed.isNotEmpty()

    @Synchronized fun onBackground(callback: () -> Unit) {
        onLeaveCallbacks.add(callback)
    }
}
