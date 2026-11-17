package com.jiacimu.lulu.system

/**
 * Input belongs to the virtual phone, not a particular third-party app.
 * Text insertion is a reversible draft operation; sending, publishing,
 * checkout and search submission remain separate explicit actions.
 */
internal object LuluVirtualTextInputPolicy {
    fun inputPointSafe(x: Int, y: Int, imeTopPermille: Int?): Boolean =
        x in 15..985 && y in 35..970 &&
            (imeTopPermille == null || y < imeTopPermille - 8)

    fun requiresVisualSnapshot(hasX: Boolean, hasY: Boolean): Boolean = hasX && hasY

    fun stage(accepted: Boolean, exactReadback: Boolean): String = when {
        exactReadback -> "draft_verified"
        accepted -> "input_dispatched_unverified"
        else -> "input_not_dispatched"
    }
}
