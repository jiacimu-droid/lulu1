package com.jiacimu.lulu.data

import android.content.Context
import java.time.Instant

/**
 * Compatibility facade for code compiled against the former proactive-message runtime.
 *
 * The old implementation contained launch-time checks, a 20-minute in-process loop, screen /
 * notification wakeups, contact cooldowns and daily action limits. Those behaviors are intentionally
 * removed. All real work now belongs to [ProactivePerceptionRuntime] and its per-character policy.
 */
object ProactiveMessageAutomation {
    private var appContext: Context? = null

    @Synchronized
    fun initialize(context: Context) {
        appContext = context.applicationContext
        ProactivePerceptionRuntime.initialize(context.applicationContext)
        ProactivePerceptionScheduler.schedule(context.applicationContext)
    }

    /** Screen / notification changes are passive context only and never trigger a model call. */
    fun signalPerceptionChange(context: Context, trigger: String) {
        appContext = context.applicationContext
        ProactivePerceptionScheduler.scheduleNextDue(context.applicationContext)
    }

    internal suspend fun checkOnce(now: Instant = Instant.now()): Boolean {
        val context = appContext ?: return false
        return ProactivePerceptionRuntime.runDueCycle(
            context = context,
            trigger = "用户手动检查",
            force = true,
            now = now,
        ) > 0
    }

    internal suspend fun runBackgroundCycle(
        trigger: String = "角色时间间隔",
        now: Instant = Instant.now(),
    ): Int {
        val context = appContext ?: return 0
        return ProactivePerceptionRuntime.runDueCycle(
            context = context,
            trigger = trigger,
            force = false,
            now = now,
        )
    }
}
