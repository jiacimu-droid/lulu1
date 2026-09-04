package com.jiacimu.lulu.games

import kotlin.math.hypot

internal data class WorldVector(
    val x: Float,
    val y: Float,
) {
    operator fun plus(other: WorldVector) = WorldVector(x + other.x, y + other.y)
    operator fun minus(other: WorldVector) = WorldVector(x - other.x, y - other.y)
    operator fun times(scale: Float) = WorldVector(x * scale, y * scale)

    val length: Float get() = hypot(x, y)

    fun normalized(): WorldVector {
        val magnitude = length
        return if (magnitude <= 0.0001f) Zero else WorldVector(x / magnitude, y / magnitude)
    }

    fun distanceTo(other: WorldVector): Float = (this - other).length

    companion object {
        val Zero = WorldVector(0f, 0f)
    }
}

internal data class WorldRectangle(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    init {
        require(right >= left && bottom >= top)
    }

    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val center: WorldVector get() = WorldVector((left + right) / 2f, (top + bottom) / 2f)

    fun expanded(amount: Float) = WorldRectangle(
        left - amount,
        top - amount,
        right + amount,
        bottom + amount,
    )
}

internal data class WorldObstacle(
    val id: String,
    val bounds: WorldRectangle,
    val label: String = "",
    val action: String = "",
)

internal data class WorldMoveResult(
    val position: WorldVector,
    val collided: Boolean,
)

/**
 * Axis-separated circle/AABB collision. Moving each axis independently lets a character slide
 * naturally along furniture, walls and wreckage instead of sticking to the first corner touched.
 */
internal fun moveInWorld(
    position: WorldVector,
    direction: WorldVector,
    speed: Float,
    deltaSeconds: Float,
    radius: Float,
    bounds: WorldRectangle,
    obstacles: List<WorldObstacle>,
): WorldMoveResult {
    if (direction.length <= 0.001f || deltaSeconds <= 0f) return WorldMoveResult(position, false)
    val velocity = direction.normalized() * (speed * deltaSeconds.coerceIn(0f, .05f))
    var collided = false

    val candidateX = WorldVector(
        (position.x + velocity.x).coerceIn(bounds.left + radius, bounds.right - radius),
        position.y,
    )
    val nextX = if (obstacles.any { circleIntersects(candidateX, radius, it.bounds) }) {
        collided = true
        position.x
    } else {
        candidateX.x
    }

    val afterX = WorldVector(nextX, position.y)
    val candidateY = WorldVector(
        nextX,
        (position.y + velocity.y).coerceIn(bounds.top + radius, bounds.bottom - radius),
    )
    val nextY = if (obstacles.any { circleIntersects(candidateY, radius, it.bounds) }) {
        collided = true
        afterX.y
    } else {
        candidateY.y
    }

    return WorldMoveResult(WorldVector(nextX, nextY), collided)
}

internal fun circleIntersects(
    center: WorldVector,
    radius: Float,
    rectangle: WorldRectangle,
): Boolean {
    val closestX = center.x.coerceIn(rectangle.left, rectangle.right)
    val closestY = center.y.coerceIn(rectangle.top, rectangle.bottom)
    val dx = center.x - closestX
    val dy = center.y - closestY
    return dx * dx + dy * dy < radius * radius
}

internal fun worldCameraTarget(
    focus: WorldVector,
    viewportWidth: Float,
    viewportHeight: Float,
    worldWidth: Float,
    worldHeight: Float,
): WorldVector = WorldVector(
    (focus.x - viewportWidth / 2f).coerceIn(0f, (worldWidth - viewportWidth).coerceAtLeast(0f)),
    (focus.y - viewportHeight / 2f).coerceIn(0f, (worldHeight - viewportHeight).coerceAtLeast(0f)),
)

internal fun WorldVector.lerp(target: WorldVector, amount: Float): WorldVector {
    val t = amount.coerceIn(0f, 1f)
    return WorldVector(x + (target.x - x) * t, y + (target.y - y) * t)
}
