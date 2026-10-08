package com.jiacimu.lulu.games

import kotlin.math.ceil

/** Short, collision-checked routes; planning runs on decisions, never on every animation frame. */
internal fun worldFollowPath(
    from: WorldVector,
    to: WorldVector,
    bounds: WorldRectangle,
    obstacles: List<WorldObstacle>,
    radius: Float,
): List<WorldVector> {
    fun walkable(p: WorldVector) = p.x >= bounds.left + radius && p.x <= bounds.right - radius &&
        p.y >= bounds.top + radius && p.y <= bounds.bottom - radius &&
        obstacles.none { circleIntersects(p, radius, it.bounds) }
    fun clear(a: WorldVector, b: WorldVector): Boolean {
        val steps = ceil(a.distanceTo(b) / 12f).toInt().coerceAtLeast(1)
        return (0..steps).all { walkable(a.lerp(b, it.toFloat() / steps)) }
    }
    if (!walkable(from) || !walkable(to)) return emptyList()
    if (clear(from, to)) return listOf(to)
    val columns = ceil(bounds.width / 56f).toInt().coerceIn(1, 64)
    val rows = ceil(bounds.height / 56f).toInt().coerceIn(1, 64)
    val nodes = List(columns * rows) { i -> WorldVector(
        bounds.left + (i % columns + .5f) * bounds.width / columns,
        bounds.top + (i / columns + .5f) * bounds.height / rows,
    ) }
    val valid = nodes.map(::walkable)
    val start = nodes.indices.filter { valid[it] }.sortedBy { nodes[it].distanceTo(from) }
        .firstOrNull { clear(from, nodes[it]) } ?: return emptyList()
    val goal = nodes.indices.filter { valid[it] }.sortedBy { nodes[it].distanceTo(to) }
        .firstOrNull { clear(nodes[it], to) } ?: return emptyList()
    val parents = IntArray(nodes.size) { -2 }
    val queue = java.util.ArrayDeque<Int>()
    parents[start] = -1
    queue.add(start)
    while (queue.isNotEmpty() && parents[goal] == -2) {
        val i = queue.removeFirst()
        val x = i % columns
        val y = i / columns
        val neighbors = listOfNotNull(
            (i - 1).takeIf { x > 0 }, (i + 1).takeIf { x + 1 < columns },
            (i - columns).takeIf { y > 0 }, (i + columns).takeIf { y + 1 < rows },
        )
        neighbors.filter { valid[it] && parents[it] == -2 && clear(nodes[i], nodes[it]) }.forEach {
            parents[it] = i
            queue.add(it)
        }
    }
    if (parents[goal] == -2) return emptyList()
    val route = mutableListOf(to)
    var cursor = goal
    while (cursor >= 0) { route.add(nodes[cursor]); cursor = parents[cursor] }
    return route.asReversed()
}
