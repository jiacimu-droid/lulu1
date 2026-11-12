package com.jiacimu.lulu.system

/** Limits expensive accessibility tree reads caused by high-frequency
 * TYPE_WINDOW_CONTENT_CHANGED / text/scroll events. This controls only the
 * passive main-display state feed; explicit owner-authorized observeDisplay
 * and tool actions are never throttled. */
internal class LuluAccessibilityEventBudget(
    private val minReadIntervalMs: Long = 750L,
    private val packageSwitchIntervalMs: Long = 220L,
) {
    private var lastObservedPackage: String? = null
    private var lastReadAtMs: Long = Long.MIN_VALUE

    fun shouldRead(eventPackage: String, nowMs: Long): Boolean {
        if (eventPackage.isBlank()) return false
        val previous = lastReadAtMs
        val elapsed = if (previous == Long.MIN_VALUE || nowMs < previous) Long.MAX_VALUE
            else nowMs - previous
        val interval = if (lastObservedPackage != eventPackage)
            packageSwitchIntervalMs else minReadIntervalMs
        if (elapsed < interval) return false
        lastObservedPackage = eventPackage
        lastReadAtMs = nowMs
        return true
    }

    fun reset() {
        lastObservedPackage = null
        lastReadAtMs = Long.MIN_VALUE
    }
}
