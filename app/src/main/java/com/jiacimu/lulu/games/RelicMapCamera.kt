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
        // Finite inputs can still overflow during subtraction/division. Never clamp an
        // out-of-range projection into a real tile through Float.toInt saturation.
        val projectedX = (px.toDouble() - offsetX.toDouble()) / tileSize.toDouble()
        val projectedY = (py.toDouble() - offsetY.toDouble()) / tileSize.toDouble()
        if (!projectedX.isFinite() || !projectedY.isFinite() ||
            projectedX < 0.0 || projectedY < 0.0 ||
            projectedX >= width.toDouble() || projectedY >= height.toDouble()) return null
        val x = floor(projectedX).toInt()
        val y = floor(projectedY).toInt()
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
    // Android may compose a zero-sized map during rotation; never divide by zero or
    // let an invalid gesture zoom propagate NaN into drawing and hit-testing.
    if (!width.isFinite() || !height.isFinite() || width <= 0f || height <= 0f ||
        columns <= 0 || rows <= 0 || !focusX.isFinite() || !focusY.isFinite() ||
        !panX.isFinite() || !panY.isFinite()) return RelicMapCamera(0f, 0f, 0f)
    val baseTile = if (fullscreen) maxOf(width / (if (width > height) 20f else 12f), height / rows)
        else min(width / columns, height / rows)
    val safeZoom = if (zoom.isFinite()) zoom.coerceIn(.75f, 1.75f) else 1f
    val tile = baseTile * safeZoom
    // A finite Android viewport/focus can still overflow during zoom and camera
    // centering. Do not publish a drawable camera with infinite coordinates.
    if (!tile.isFinite() || tile <= 0f) return RelicMapCamera(0f, 0f, 0f)
    if (!fullscreen) return RelicMapCamera(tile, 0f, 0f)
    val ox = width / 2f - (focusX + .5f) * tile + panX
    val oy = height / 2f - (focusY + .5f) * tile + panY
    if (!ox.isFinite() || !oy.isFinite()) return RelicMapCamera(0f, 0f, 0f)
    // Permit space beyond board edges: clamping put the spawn beneath the top-left HUD.
    return RelicMapCamera(tile, ox, oy)
}

/** Exclusive board bounds visible on screen, with one tile of overscan.
 * Use Double before clamping: Float-to-Int saturation near a far pan must not
 * turn an entirely offscreen board into a rendered edge strip.
 */
internal fun RelicMapCamera.visibleBounds(
    viewportWidth: Float, viewportHeight: Float, columns: Int, rows: Int,
    overscan: Int = 1
): IntArray {
    if (tileSize <= 0f || !tileSize.isFinite() || !offsetX.isFinite() ||
        !offsetY.isFinite() || !viewportWidth.isFinite() || !viewportHeight.isFinite() ||
        viewportWidth <= 0f || viewportHeight <= 0f || columns <= 0 || rows <= 0) {
        return intArrayOf(0, 0, 0, 0)
    }
    val tile = tileSize.toDouble()
    fun start(offset: Float, count: Int): Int =
        kotlin.math.floor(-offset.toDouble() / tile - overscan).coerceIn(0.0, count.toDouble()).toInt()
    fun end(viewport: Float, offset: Float, count: Int): Int =
        kotlin.math.ceil((viewport.toDouble() - offset.toDouble()) / tile + overscan)
            .coerceIn(0.0, count.toDouble()).toInt()
    val left = start(offsetX, columns)
    val right = end(viewportWidth, offsetX, columns)
    val top = start(offsetY, rows)
    val bottom = end(viewportHeight, offsetY, rows)
    return intArrayOf(left, maxOf(left, right), top, maxOf(top, bottom))
}
