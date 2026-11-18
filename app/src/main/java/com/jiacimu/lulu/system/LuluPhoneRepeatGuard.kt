package com.jiacimu.lulu.system

/** Mika ClickFallback-inspired guard: never fire the identical irreversible
 * or ambiguous gesture twice merely because the screen has not visibly
 * changed. A read alone does not prove that the screen changed.
 * The original character still decides which DIFFERENT action to try. */
internal object LuluPhoneRepeatGuard {
    data class Attempt(
        val action: String,
        val target: String,
        val accepted: Boolean,
        val effect: String,
    )

    data class Screen(val place: String, val tree: String, val text: String, val image: String?)

    /** null means there is not enough shared evidence to compare the screens. */
    fun changed(before: Screen?, after: Screen?): Boolean? {
        if (before == null || after == null) return null
        if (before.place != after.place) return true
        if (before.tree.isNotBlank() && after.tree.isNotBlank()) {
            if (before.tree != after.tree || before.text != after.text) return true
            // A verified zero-node root still has a fingerprint. It must not
            // erase real visual progress between two different screens.
            if (before.text.isBlank() && after.text.isBlank() && before.image != null && after.image != null)
                return before.image != after.image
            return false
        }
        if (before.text.isNotBlank() && after.text.isNotBlank() && before.text != after.text) return true
        if (before.image != null && after.image != null) return before.image != after.image
        return null
    }

    fun isRepeatedRead(before: Screen?, after: Screen?): Boolean =
        changed(before, after) == false &&
            (before?.image == null || after?.image == null || before.image == after.image)

    fun target(action: String, args: Map<String, String>, stableElement: String? = null): String = when (action) {
        "tap", "long_press" -> listOf("x", "y").joinToString(":") { args[it].orEmpty() }
        "tap_element" -> stableElement ?: listOf("screenSnapshotId", "elementRef")
            .joinToString(":") { args[it].orEmpty() }
        "swipe" -> listOf("startX", "startY", "endX", "endY")
            .joinToString(":") { args[it].orEmpty() }
        "submit" -> "enter"
        "back" -> "back"
        "search" -> args["text"].orEmpty().trim()
        // A Unicode paste may already have landed even when HyperOS hides
        // the text field from accessibility/OCR. Repeating the same paste
        // would append a duplicate draft rather than recover the first one.
        // Changing the field or the draft is a distinct model decision.
        "type" -> listOf(args["text"].orEmpty(), args["inputX"].orEmpty(),
            args["inputY"].orEmpty(), args["editorRef"].orEmpty()).joinToString("|")
        "launch", "adopt_app" -> args["packageName"].orEmpty().trim().ifEmpty { args["appName"].orEmpty().trim() }
        else -> ""
    }

    fun shouldBlock(last: Attempt?, action: String, target: String): Boolean {
        if (target.isBlank() || last == null || last.action != action || last.target != target) return false
        if (last.effect == "known_rejection") return action == "search" || action == "launch"
        return action in setOf("tap", "long_press", "tap_element", "swipe", "back", "submit", "search", "type") &&
            last.accepted && last.effect in setOf("tree_unchanged", "unknown")
    }
}
