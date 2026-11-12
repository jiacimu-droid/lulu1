package com.jiacimu.lulu.system

/** Mika ClickFallback-inspired guard: never fire the identical irreversible
 * or ambiguous gesture twice merely because the screen has not visibly
 * changed. An explicit new observe/changed target is required first.
 * The original character still decides which DIFFERENT action to try. */
internal object LuluPhoneRepeatGuard {
    data class Attempt(
        val action: String,
        val target: String,
        val accepted: Boolean,
        val effect: String,
    )

    fun target(action: String, args: Map<String, String>): String = when (action) {
        "tap" -> listOf("x", "y").joinToString(":") { args[it].orEmpty() }
        "tap_element" -> listOf("screenSnapshotId", "elementRef")
            .joinToString(":") { args[it].orEmpty() }
        "swipe" -> listOf("startX", "startY", "endX", "endY")
            .joinToString(":") { args[it].orEmpty() }
        "submit" -> "enter"
        else -> ""
    }

    fun shouldBlock(last: Attempt?, action: String, target: String): Boolean {
        if (action !in setOf("tap", "tap_element", "swipe", "submit") ||
            target.isBlank() || last == null) return false
        return last.action == action && last.target == target &&
            last.accepted && last.effect in setOf("tree_unchanged", "unknown")
    }
}
