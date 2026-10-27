package com.jiacimu.lulu

/** Keep the selected contact tool through DOWN, MOVE and release. */
internal object Live2DTouchGesture {
    /** Validate actual lifecycle facts, never reassign a stale touch to a new call. */
    fun acceptsCompletion(beganCallId: String, currentCallId: String,
        rendererReady: Boolean, stageAlive: Boolean): Boolean =
        beganCallId == currentCallId && rendererReady && stageAlive

    /**
     * Gesture sampling is a local display operation, never an AI call.
     * Distance accumulates across MOVE frames; a viewport exit/re-entry
     * cannot wait for the next 70ms tick or the old contact stays active.
     */
    fun shouldDispatchMove(
        unsentTravel: Float, sinceLastSentMillis: Long, touchSlop: Float,
        crossedViewportBoundary: Boolean = false,
    ): Boolean {
        if (!unsentTravel.isFinite() || !touchSlop.isFinite()) return false
        if (crossedViewportBoundary) return true
        return sinceLastSentMillis >= 70L &&
            unsentTravel >= maxOf(1f, touchSlop.coerceAtLeast(0f) * .15f)
    }

    fun performed(mode: String, handGesture: String): String = when (mode) {
        "kiss" -> "kiss"
        "pat" -> when (handGesture) {
            "stroke-left" -> "slap-left"
            "stroke-right" -> "slap-right"
            else -> "slap"
        }
        else -> handGesture
    }

    /**
     * Classify the completed path, not its last 70ms MOVE fragment. A quick
     * reversal at release must not turn a rightward cheek swipe into a
     * leftward strike. This is purely screen-space geometry, not an emotion
     * or an interpretation of the user's intent.
     */
    fun releasedGesture(
        dx: Float, dy: Float, travel: Float, touchSlop: Float,
        elapsedMillis: Long, lastStroke: String?,
    ): String {
        if (!dx.isFinite() || !dy.isFinite() || !travel.isFinite() ||
            !touchSlop.isFinite()) return "tap"
        if (travel <= touchSlop.coerceAtLeast(0f) * 3f) {
            return if (elapsedMillis >= 520L) "hold" else "tap"
        }
        // A coherent net path wins. A back-and-forth caress with little net
        // displacement retains its last real stroke instead of inventing a
        // lateral slap direction from a nearly zero delta.
        val net = kotlin.math.hypot(dx, dy)
        if (net > touchSlop.coerceAtLeast(0f) &&
            net >= travel * .22f) {
            return if (kotlin.math.abs(dx) >= kotlin.math.abs(dy)) {
                if (dx >= 0f) "stroke-right" else "stroke-left"
            } else {
                if (dy >= 0f) "stroke-down" else "stroke-up"
            }
        }
        return lastStroke?.takeIf { it in setOf(
            "stroke-left", "stroke-right", "stroke-up", "stroke-down",
        ) } ?: "hold"
    }
}

internal data class Live2DTouchContact(
    val region: String,
    val gesture: String,
    val force: Float,
    val durationMillis: Long,
)
