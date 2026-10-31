package com.jiacimu.lulu.games

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * One world-to-screen transform for rendering and hit testing the island.
 * Focus is an interpolated tile coordinate, not the last committed grid position.
 * World edges never clamp the camera: the player's screen position stays centered.
 */
internal data class IslandFollowCamera(
    val tileSize: Float,
    val originX: Float,
    val originY: Float,
    val firstColumn: Int,
    val endColumnExclusive: Int,
    val firstRow: Int,
    val endRowExclusive: Int,
) {
    fun tileAt(px: Float, py: Float, worldWidth: Int, worldHeight: Int): Pair<Int, Int>? {
        val x = floor((px - originX) / tileSize).toInt()
        val y = floor((py - originY) / tileSize).toInt()
        return if (x in 0 until worldWidth && y in 0 until worldHeight) x to y else null
    }
}

internal fun islandFollowCamera(
    width: Float, height: Float, columns: Int, rows: Int,
    focusX: Float, focusY: Float, worldWidth: Int, worldHeight: Int,
): IslandFollowCamera {
    require(width > 0f && height > 0f && columns > 0 && rows > 0)
    require(worldWidth > 0 && worldHeight > 0)
    val tile = min(width / columns, height / rows)
    val originX = width / 2f - (focusX + .5f) * tile
    val originY = height / 2f - (focusY + .5f) * tile
    return IslandFollowCamera(
        tileSize = tile,
        originX = originX,
        originY = originY,
        firstColumn = floor(-originX / tile).toInt().coerceIn(0, worldWidth),
        endColumnExclusive = ceil((width - originX) / tile).toInt().coerceIn(0, worldWidth),
        firstRow = floor(-originY / tile).toInt().coerceIn(0, worldHeight),
        endRowExclusive = ceil((height - originY) / tile).toInt().coerceIn(0, worldHeight),
    )
}
