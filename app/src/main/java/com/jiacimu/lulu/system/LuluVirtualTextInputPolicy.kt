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

    fun requiresVisualSnapshot(hasX: Boolean, hasY: Boolean): Boolean = hasX && hasY

    fun stage(accepted: Boolean, exactReadback: Boolean): String = when {
        !accepted -> "input_not_dispatched"
        exactReadback -> "draft_verified"
        else -> "input_dispatched_unverified"
    }
}
