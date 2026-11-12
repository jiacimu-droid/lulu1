package com.jiacimu.lulu.system

/** A missing ImageReader frame is not a failed task when the independent
 * display still provides fresh, target-owned accessibility evidence.
 * This is a LOCAL progress guard, not an agent deciding to browse or stop. */
internal object LuluVirtualNoFramePolicy {
    const val MAX_STALLED_STEPS = 4

    fun nextStalledCount(
        previous: Int,
        hasFrame: Boolean,
        targetWindowVerified: Boolean,
        effectKind: String,
        currentFingerprint: String?,
        previousFingerprint: String?,
        inputReadbackVerified: Boolean,
    ): Int {
        if (hasFrame) return 0
        val evidenceChanged = !currentFingerprint.isNullOrBlank() &&
            !previousFingerprint.isNullOrBlank() && currentFingerprint != previousFingerprint
        val progressed = targetWindowVerified && (
            evidenceChanged || inputReadbackVerified ||
                effectKind in setOf("tree_changed", "app_visible", "input_verified"))
        return if (progressed) 0 else (previous + 1).coerceAtMost(MAX_STALLED_STEPS)
    }

    fun exhausted(stalled: Int): Boolean = stalled >= MAX_STALLED_STEPS
}
