package com.jiacimu.lulu.games

import kotlin.math.floor
import kotlin.math.min

/** Drawing and touch use exactly the same camera; camera changes never move an actor. */
internal data class RelicMapCamera(val tileSize: Float, val offsetX: Float, val offsetY: Float) {
    fun tileAt(px: Float, py: Float, width: Int, height: Int): Pair<Int, Int>? {
        // A cancelled pinch or a zero-sized first layout can supply non-finite coordinates.
        // Float.toInt() maps NaN to zero, otherwise selecting a real corner tile by accident.
        if (!px.isFinite() || !py.isFinite() || !tileSize.isFinite() || tileSize <= 0f ||
            !offsetX.isFinite() || !offsetY.isFinite()) return null
        val x = floor((px - offsetX) / tileSize).toInt()
        val y = floor((py - offsetY) / tileSize).toInt()
        return if (x in 0 until width && y in 0 until height) x to y else null
    }
}

internal fun relicMapCamera(width: Float, height: Float, columns: Int, rows: Int,
    fullscreen: Boolean, focus: Pair<Int, Int>): RelicMapCamera =
    relicMapCamera(width, height, columns, rows, fullscreen,
        focus.first.toFloat(), focus.second.toFloat())

/** Smooth camera projection of the real integer-grid movement state. */
internal fun relicMapCamera(width: Float, height: Float, columns: Int, rows: Int,
    fullscreen: Boolean, focusX: Float, focusY: Float,
    zoom: Float = 1f, panX: Float = 0f, panY: Float = 0f): RelicMapCamera {
    val baseTile = if (fullscreen) maxOf(width / (if (width > height) 20f else 12f), height / rows)
        else min(width / columns, height / rows)
    val tile = baseTile * zoom.coerceIn(.75f, 1.75f)
    // Permit space beyond board edges: clamping put the spawn beneath the top-left HUD.
    return if (fullscreen) RelicMapCamera(tile,
        width / 2f - (focusX + .5f) * tile + panX,
        height / 2f - (focusY + .5f) * tile + panY)
    else RelicMapCamera(tile, 0f, 0f)
}
