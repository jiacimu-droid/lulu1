package com.jiacimu.lulu.games

/** Small compatibility helper for selecting the tail of IntRange without materializing unrelated history. */
internal fun IntRange.takeLast(count: Int): List<Int> {
    if (count <= 0 || isEmpty()) return emptyList()
    val amount = count.coerceAtMost(last - first + 1)
    val start = (last - amount + 1).coerceAtLeast(first)
    return (start..last).toList()
}
