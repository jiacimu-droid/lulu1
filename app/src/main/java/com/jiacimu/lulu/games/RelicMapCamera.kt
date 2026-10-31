package com.jiacimu.lulu.games

import kotlin.math.floor
import kotlin.math.min

/** Drawing and touch use exactly the same camera; camera changes never move an actor. */
internal data class RelicMapCamera(val tileSize: Float, val offsetX: Float, val offsetY: Float) {
    fun tileAt(px: Float, py: Float, width: Int, height: Int): Pair<Int, Int>? {
        val x = floor((px - offsetX) / tileSize).toInt()
        val y = floor((py - offsetY) / tileSize).toInt()
        return if (x in 0 until width && y in 0 until height) x to y else null
    }
}

internal fun relicMapCamera(width: Float, height: Float, columns: Int, rows: Int,
    fullscreen: Boolean, focus: Pair<Int, Int>): RelicMapCamera {
    val tile = if (fullscreen) maxOf(width / (if (width > height) 20f else 12f), height / rows)
        else min(width / columns, height / rows)
    // Permit space beyond board edges: clamping put the spawn beneath the top-left HUD.
    return if (fullscreen) RelicMapCamera(tile,
        width / 2f - (focus.first + .5f) * tile, height / 2f - (focus.second + .5f) * tile)
    else RelicMapCamera(tile, 0f, 0f)
}
