package com.jiacimu.lulu.system

/**
 * Input belongs to the virtual phone, not a particular third-party app.
 * Text insertion is a reversible draft operation; sending, publishing,
 * checkout and search submission remain separate explicit actions.
 */
internal object LuluVirtualTextInputPolicy {
    // Bounded to fit the shell transport's 4000-character JSON line,
    // but not the old search-only 500-character limit. Long drafts are
    // independent of chat / search / purchase submission constraints.
    const val MAX_TEXT_CHARS = 1800

    fun inputPointSafe(x: Int, y: Int, imeTopPermille: Int?): Boolean =
        x in 15..985 && y in 35..970 &&
            (imeTopPermille == null || y < imeTopPermille - 8)

    /** A visual click may expose a focused accessibility editor only AFTER
     * the tap. Authorize an accessibility write solely when the actual field
     * contains the model-selected screenshot point on the SAME display. */
    fun focusedFieldContainsSelectedPoint(
        x: Int, y: Int, displayWidth: Int, displayHeight: Int,
        left: Int, top: Int, right: Int, bottom: Int,
    ): Boolean {
        if (displayWidth <= 1 || displayHeight <= 1 ||
            !inputPointSafe(x, y, null) || left >= right || top >= bottom ||
            left < 0 || top < 0 || right > displayWidth || bottom > displayHeight ||
            ((right - left) >= displayWidth * 95L / 100L &&
                (bottom - top) >= displayHeight * 70L / 100L)) return false
        val px = ((displayWidth - 1L) * x / 1000L).toInt()
        val py = ((displayHeight - 1L) * y / 1000L).toInt()
        return px >= left && px < right && py >= top && py < bottom
    }

    fun requiresVisualSnapshot(hasX: Boolean, hasY: Boolean): Boolean = hasX && hasY

    enum class VisualInputRoute { WAIT, SET_TEXT, TARGETED_PASTE }

    /** Mika's explicit-point path can use a focused app window for a custom
     * editor, even when Android does not list a soft keyboard on that display. */
    fun visualInputRoute(
        safeFocus: Boolean, hasKnownEditor: Boolean, matchesEditor: Boolean,
        supportsSetText: Boolean, sameWindow: Boolean, targetWindowFocused: Boolean,
        localImeVisible: Boolean,
    ): VisualInputRoute = when {
        !safeFocus || (hasKnownEditor && !matchesEditor) -> VisualInputRoute.WAIT
        matchesEditor && supportsSetText -> VisualInputRoute.SET_TEXT
        (sameWindow || matchesEditor) && (targetWindowFocused || localImeVisible) ->
            VisualInputRoute.TARGETED_PASTE
        else -> VisualInputRoute.WAIT
    }

    fun stage(accepted: Boolean, exactReadback: Boolean): String = when {
        !accepted -> "input_not_dispatched"
        exactReadback -> "draft_verified"
        else -> "input_dispatched_unverified"
    }
}
